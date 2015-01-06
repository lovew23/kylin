/*
 * Copyright 2013-2014 eBay Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kylinolap.invertedindex;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kylinolap.common.KylinConfig;
import com.kylinolap.common.persistence.JsonSerializer;
import com.kylinolap.common.persistence.ResourceStore;
import com.kylinolap.common.persistence.Serializer;
import com.kylinolap.common.restclient.Broadcaster;
import com.kylinolap.common.restclient.SingleValueCache;
import com.kylinolap.dict.DateStrDictionary;
import com.kylinolap.dict.Dictionary;
import com.kylinolap.dict.DictionaryInfo;
import com.kylinolap.dict.DictionaryManager;
import com.kylinolap.dict.lookup.SnapshotManager;
import com.kylinolap.invertedindex.model.IIDesc;
import com.kylinolap.metadata.MetadataManager;
import com.kylinolap.metadata.model.SegmentStatusEnum;
import com.kylinolap.metadata.model.TblColRef;

/**
 * @author honma
 */
public class IIManager {

    private static String ALPHA_NUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static int HBASE_TABLE_LENGTH = 10;

    private static final Serializer<IIInstance> II_SERIALIZER = new JsonSerializer<IIInstance>(IIInstance.class);

    private static final Logger logger = LoggerFactory.getLogger(IIManager.class);

    // static cached instances
    private static final ConcurrentHashMap<KylinConfig, IIManager> CACHE = new ConcurrentHashMap<KylinConfig, IIManager>();

    public static IIManager getInstance(KylinConfig config) {
        IIManager r = CACHE.get(config);
        if (r != null) {
            return r;
        }

        synchronized (IIManager.class) {
            r = CACHE.get(config);
            if (r != null) {
                return r;
            }
            try {
                r = new IIManager(config);
                CACHE.put(config, r);
                if (CACHE.size() > 1) {
                    logger.warn("More than one singleton exist");
                }
                return r;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to init IIManager from " + config, e);
            }
        }
    }

    public static void clearCache() {
        CACHE.clear();
    }

    public static synchronized void removeInstance(KylinConfig config) {
        CACHE.remove(config);
    }

    // ============================================================================

    private KylinConfig config;
    // ii name ==> IIInstance
    private SingleValueCache<String, IIInstance> iiMap = new SingleValueCache<String, IIInstance>(Broadcaster.TYPE.CUBE);

    // for generation hbase table name of a new segment
    private HashSet<String> usedStorageLocation = new HashSet<String>();

    private IIManager(KylinConfig config) throws IOException {
        logger.info("Initializing IIManager with config " + config);
        this.config = config;

        loadAllIIInstance();
    }

    public List<IIInstance> listAllIIs() {
        return new ArrayList<IIInstance>(iiMap.values());
    }

    public IIInstance getII(String iiName) {
        iiName = iiName.toUpperCase();
        return iiMap.get(iiName);
    }

    public List<IIInstance> getIIsByDesc(String descName) {

        descName = descName.toUpperCase();
        List<IIInstance> list = listAllIIs();
        List<IIInstance> result = new ArrayList<IIInstance>();
        Iterator<IIInstance> it = list.iterator();
        while (it.hasNext()) {
            IIInstance ci = it.next();
            if (descName.equals(ci.getDescName())) {
                result.add(ci);
            }
        }
        return result;
    }

    public void buildInvertedIndexDictionary(IISegment iiSeg, String factColumnsPath) throws IOException {
        DictionaryManager dictMgr = getDictionaryManager();
        IIDesc iiDesc = iiSeg.getIIInstance().getDescriptor();
        for (TblColRef column : iiDesc.listAllColumns()) {
            if (iiDesc.isMetricsCol(column)) {
                continue;
            }

            DictionaryInfo dict = dictMgr.buildDictionary(iiDesc.getModel(), "true", column, factColumnsPath);
            iiSeg.putDictResPath(column, dict.getResourcePath());
        }
        saveResource(iiSeg.getIIInstance());
    }

    /**
     * return null if no dictionary for given column
     */
    public Dictionary<?> getDictionary(IISegment iiSeg, TblColRef col) {
        DictionaryInfo info = null;
        try {
            DictionaryManager dictMgr = getDictionaryManager();
            // logger.info("Using metadata url " + metadataUrl +
            // " for DictionaryManager");
            String dictResPath = iiSeg.getDictResPath(col);
            if (dictResPath == null)
                return null;

            info = dictMgr.getDictionaryInfo(dictResPath);
            if (info == null)
                throw new IllegalStateException("No dictionary found by " + dictResPath + ", invalid II state; II segment" + iiSeg + ", col " + col);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to get dictionary for II segment" + iiSeg + ", col" + col, e);
        }

        return info.getDictionaryObject();
    }

    public IIInstance updateII(IIInstance ii) throws IOException {
        logger.info("Updating II instance '" + ii.getName());

        // save resource
        saveResource(ii);

        logger.info("II with " + ii.getSegments().size() + " segments is saved");

        return ii;
    }

    public static String getHBaseStorageLocationPrefix() {
        return "KYLIN_II_";
    }

    /**
     * For each htable, we leverage htable's metadata to keep track of which
     * kylin server(represented by its kylin_metadata prefix) owns this htable
     */
    public static String getHtableMetadataKey() {
        return "KYLIN_HOST";
    }

    public void loadIICache(IIInstance ii) {
        try {
            loadIIInstance(ii.getResourcePath());
        } catch (IOException e) {
            logger.error(e.getLocalizedMessage(), e);
        }
    }

    public void removeIICache(IIInstance ii) {
        iiMap.remove(ii.getName().toUpperCase());

        for (IISegment segment : ii.getSegments()) {
            usedStorageLocation.remove(segment.getName());
        }
    }

    private void saveResource(IIInstance ii) throws IOException {
        ResourceStore store = getStore();
        store.putResource(ii.getResourcePath(), ii, II_SERIALIZER);
        this.afterIIUpdated(ii);
    }

    private void afterIIUpdated(IIInstance updatedII) {
        MetadataManager.getInstance(config).reload();
        iiMap.put(updatedII.getName().toUpperCase(), updatedII);
    }

    /**
     * @param IIInstance
     * @param startDate
     *            (pass 0 if full build)
     * @param endDate
     *            (pass 0 if full build)
     * @return
     */
    private IISegment buildSegment(IIInstance IIInstance, long startDate, long endDate) {
        IISegment segment = new IISegment();
        String incrementalSegName = IISegment.getSegmentName(startDate, endDate);
        segment.setUuid(UUID.randomUUID().toString());
        segment.setName(incrementalSegName);
        segment.setCreateTime(DateStrDictionary.dateToString(new Date()));
        segment.setDateRangeStart(startDate);
        segment.setDateRangeEnd(endDate);
        segment.setStatus(SegmentStatusEnum.NEW);
        segment.setStorageLocationIdentifier(generateStorageLocation());

        segment.setIIInstance(IIInstance);

        return segment;
    }

    private String generateStorageLocation() {
        String namePrefix = getHBaseStorageLocationPrefix();
        String tableName = "";
        do {
            StringBuffer sb = new StringBuffer();
            sb.append(namePrefix);
            for (int i = 0; i < HBASE_TABLE_LENGTH; i++) {
                int idx = (int) (Math.random() * ALPHA_NUM.length());
                sb.append(ALPHA_NUM.charAt(idx));
            }
            tableName = sb.toString();
        } while (this.usedStorageLocation.contains(tableName));

        return tableName;
    }

    private void loadAllIIInstance() throws IOException {
        ResourceStore store = getStore();
        List<String> paths = store.collectResourceRecursively(ResourceStore.II_RESOURCE_ROOT, ".json");

        logger.debug("Loading II from folder " + store.getReadableResourcePath(ResourceStore.II_RESOURCE_ROOT));

        for (String path : paths) {
            loadIIInstance(path);
        }

        logger.debug("Loaded " + paths.size() + " II(s)");
    }

    private synchronized IIInstance loadIIInstance(String path) throws IOException {
        ResourceStore store = getStore();
        logger.debug("Loading IIInstance " + store.getReadableResourcePath(path));

        IIInstance IIInstance = null;
        try {
            IIInstance = store.getResource(path, IIInstance.class, II_SERIALIZER);
            IIInstance.setConfig(config);

            if (StringUtils.isBlank(IIInstance.getName()))
                throw new IllegalStateException("IIInstance name must not be blank");

            iiMap.putLocal(IIInstance.getName().toUpperCase(), IIInstance);

            for (IISegment segment : IIInstance.getSegments()) {
                usedStorageLocation.add(segment.getName());
            }

            return IIInstance;
        } catch (Exception e) {
            logger.error("Error during load ii instance " + path, e);
            return null;
        }
    }

    private MetadataManager getMetadataManager() {
        return MetadataManager.getInstance(config);
    }

    private DictionaryManager getDictionaryManager() {
        return DictionaryManager.getInstance(config);
    }

    private SnapshotManager getSnapshotManager() {
        return SnapshotManager.getInstance(config);
    }

    private ResourceStore getStore() {
        return ResourceStore.getStore(this.config);
    }
}
