package org.infinispan.persistence.support;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;

import org.infinispan.Cache;
import org.infinispan.commons.test.CommonsTestingUtil;
import org.infinispan.commons.util.Util;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.persistence.file.SingleFileStore;
import org.infinispan.persistence.manager.PersistenceManager;
import org.infinispan.persistence.spi.AdvancedLoadWriteStore;
import org.infinispan.persistence.spi.MarshallableEntry;
import org.infinispan.test.AbstractInfinispanTest;
import org.infinispan.test.TestingUtil;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.infinispan.transaction.TransactionMode;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

/**
 *  @author mgencur@redhat.com
 */
@Test(groups = "functional", testName = "persistence.support.AsyncStoreWithoutEvictionFunctionalTest")
public class AsyncStoreWithoutEvictionFunctionalTest extends AbstractInfinispanTest {

   private EmbeddedCacheManager dcm;

   private boolean segmented;
   private String tmpDirectory;

   @AfterClass(alwaysRun = true)
   public void clearTempDir() {
      if (tmpDirectory != null) {
         Util.recursiveFileRemove(tmpDirectory);
      }
   }

   AsyncStoreWithoutEvictionFunctionalTest segmented(boolean segmented) {
      this.segmented = segmented;
      return this;
   }

   @Factory
   public Object[] factory() {
      return new Object[]{
            new AsyncStoreWithoutEvictionFunctionalTest().segmented(true),
            new AsyncStoreWithoutEvictionFunctionalTest().segmented(false),
      };
   }

   @Override
   protected String parameters() {
      return "segmented-" + segmented;
   }

   private EmbeddedCacheManager configureCacheManager() {

      tmpDirectory = CommonsTestingUtil.tmpDirectory(this.getClass());
      Util.recursiveFileRemove(tmpDirectory);

      GlobalConfigurationBuilder glob = new GlobalConfigurationBuilder().nonClusteredDefault()
            .defaultCacheName("cache");
      glob.globalState().persistentLocation(tmpDirectory);
      ConfigurationBuilder cacheb = new ConfigurationBuilder();
      cacheb.clustering().cacheMode(CacheMode.LOCAL)
            .transaction().transactionMode(TransactionMode.NON_TRANSACTIONAL)
            .persistence().addSingleFileStore().segmented(segmented)
            .async().enable().threadPoolSize(10).modificationQueueSize(1000);

      return TestCacheManagerFactory.createCacheManager(glob, cacheb);
   }

   @BeforeClass
   public void setUp() {
      dcm = configureCacheManager();
   }

   @AfterClass
   public void tearDown() throws Exception {
      TestingUtil.killCacheManagers(dcm);
   }

   @Test
   public void testPutRemove() throws Exception {
      final int number = 200;
      String key = "testPutRemove-k-";
      String value = "testPutRemove-v-";
      doTestPut(dcm.getCache(), number, key, value);
      doTestRemove(dcm.getCache(), number, key);
   }


   @Test
   public void testPutClearPut() throws Exception {
      final int number = 200;
      String key = "testPutClearPut-k-";
      String value = "testPutClearPut-v-";
      doTestPut(dcm.getCache(), number, key, value);
      doTestClear(dcm.getCache(), number, key);
      value = "testPutClearPut-v[2]-";
      doTestPut(dcm.getCache(), number, key, value);
      doTestRemove(dcm.getCache(), number, key);
   }

   @Test
   public void testMultiplePutsOnSameKey() throws Exception {
      final int number = 200;
      String key = "testMultiplePutsOnSameKey-k";
      String value = "testMultiplePutsOnSameKey-v-";
      doTestSameKeyPut(dcm.getCache(), number, key, value);
      doTestSameKeyRemove(dcm.getCache(), key);
   }

   private void doTestPut(Cache<Object, Object> cache, int number, String key, String value) throws Exception {
      for (int i = 0; i < number; i++) {
         cache.put(key + i, value + i);
      }

      cache.stop();
      cache.start();
      AdvancedLoadWriteStore store = getFileStoreFromDCM();

      MarshallableEntry[] entries = new MarshallableEntry[number];
      for (int i = 0; i < number; i++) {
         entries[i] = store.loadEntry(key + i);
      }

      for (int i = 0; i < number; i++) {
         MarshallableEntry entry = entries[i];

         if (entry != null) {
            assertEquals(value + i, entry.getValue());
         } else {
            while (entry == null) {
               entry = store.loadEntry(key + i);
               if (entry != null) {
                  assertEquals(value + i, entry.getValue());
               } else {
                  TestingUtil.sleepThread(20, "still waiting for key to appear: " + key + i);
               }
            }
         }
      }
   }

   private void doTestSameKeyPut(Cache<Object, Object> cache, int number, String key, String value) throws Exception {
      for (int i = 0; i < number; i++) {
         cache.put(key, value + i);
      }

      cache.stop();
      cache.start();
      AdvancedLoadWriteStore store = getFileStoreFromDCM();
      MarshallableEntry entry;
      boolean success = false;
      for (int i = 0; i < 120; i++) {
         TestingUtil.sleepThread(20, null);
         entry = store.loadEntry(key);
         success = entry.getValue().equals(value + (number - 1));
         if (success)
            break;
      }
      assertTrue(success);
   }

   private void doTestSameKeyRemove(Cache<Object, Object> cache, String key) throws Exception {
      AdvancedLoadWriteStore store = getFileStoreFromDCM();
      cache.remove(key);
      MarshallableEntry entry;
      do {
         TestingUtil.sleepThread(20, "still waiting for key to be removed: " + key);
         entry = store.loadEntry(key);
      } while (entry != null);
   }

   private void doTestRemove(Cache<Object, Object> cache, int number, String key) throws Exception {
      for (int i = 0; i < number; i++)
         cache.remove(key + i);

      cache.stop();
      cache.start();
      AdvancedLoadWriteStore store = getFileStoreFromDCM();

      MarshallableEntry[] entries = new MarshallableEntry[number];
      for (int i = 0; i < number; i++) {
         entries[i] = store.loadEntry(key + i);
      }

      for (int i = 0; i < number; i++) {
         MarshallableEntry entry = entries[i];
         while (entry != null) {
            TestingUtil.sleepThread(20, "still waiting for key to be removed: " + key + i);
            entry = store.loadEntry(key + i);
         }
      }
   }

   private void doTestClear(Cache<Object, Object> cache, int number, String key) throws Exception {
      AdvancedLoadWriteStore store = getFileStoreFromDCM();
      store.clear();
      cache.stop();
      cache.start();
      store = getFileStoreFromDCM();

      MarshallableEntry[] entries = new MarshallableEntry[number];
      for (int i = 0; i < number; i++) {
         entries[i] = store.loadEntry(key + i);
      }

      for (int i = 0; i < number; i++) {
         MarshallableEntry entry = entries[i];
         while (entry != null) {
            TestingUtil.sleepThread(20, "still waiting for key to be removed: " + key + i);
            entry = store.loadEntry(key + i);
         }
      }
   }

   private AdvancedLoadWriteStore getFileStoreFromDCM() {
      Class<? extends AdvancedLoadWriteStore> storeClass = segmented ? ComposedSegmentedLoadWriteStore.class : SingleFileStore.class;
      PersistenceManager persistenceManager = TestingUtil.extractComponent(dcm.getCache(), PersistenceManager.class);
      return persistenceManager.getStores(storeClass).iterator().next();
   }
}
