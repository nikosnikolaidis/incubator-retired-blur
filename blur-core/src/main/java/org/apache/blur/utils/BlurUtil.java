package org.apache.blur.utils;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import static org.apache.blur.metrics.MetricsConstants.BLUR;
import static org.apache.blur.metrics.MetricsConstants.ORG_APACHE_BLUR;
import static org.apache.blur.metrics.MetricsConstants.THRIFT_CALLS;
import static org.apache.blur.utils.BlurConstants.SHARD_PREFIX;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.regex.Pattern;

import org.apache.blur.log.Log;
import org.apache.blur.log.LogFactory;
import org.apache.blur.manager.clusterstatus.ZookeeperPathConstants;
import org.apache.blur.manager.results.BlurResultComparator;
import org.apache.blur.manager.results.BlurResultIterable;
import org.apache.blur.manager.results.BlurResultPeekableIteratorComparator;
import org.apache.blur.manager.results.PeekableIterator;
import org.apache.blur.thirdparty.thrift_0_9_0.TBase;
import org.apache.blur.thirdparty.thrift_0_9_0.TException;
import org.apache.blur.thirdparty.thrift_0_9_0.protocol.TJSONProtocol;
import org.apache.blur.thirdparty.thrift_0_9_0.transport.TMemoryBuffer;
import org.apache.blur.thrift.generated.Blur.Iface;
import org.apache.blur.thrift.generated.BlurQuery;
import org.apache.blur.thrift.generated.BlurResult;
import org.apache.blur.thrift.generated.BlurResults;
import org.apache.blur.thrift.generated.Column;
import org.apache.blur.thrift.generated.FetchResult;
import org.apache.blur.thrift.generated.Record;
import org.apache.blur.thrift.generated.RecordMutation;
import org.apache.blur.thrift.generated.RecordMutationType;
import org.apache.blur.thrift.generated.Row;
import org.apache.blur.thrift.generated.RowMutation;
import org.apache.blur.thrift.generated.RowMutationType;
import org.apache.blur.thrift.generated.Selector;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.SlowCompositeReaderWrapper;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.RamUsageEstimator;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.MetricName;

public class BlurUtil {

  private static final Object[] EMPTY_OBJECT_ARRAY = new Object[] {};
  private static final Class<?>[] EMPTY_PARAMETER_TYPES = new Class[] {};
  private static final Log LOG = LogFactory.getLog(BlurUtil.class);
  private static final String UNKNOWN = "UNKNOWN";
  private static Pattern validator = Pattern.compile("^[a-zA-Z0-9\\_\\-]+$");

  public static final Comparator<? super PeekableIterator<BlurResult>> HITS_PEEKABLE_ITERATOR_COMPARATOR = new BlurResultPeekableIteratorComparator();
  public static final Comparator<? super BlurResult> HITS_COMPARATOR = new BlurResultComparator();
  public static final Term PRIME_DOC_TERM = new Term(BlurConstants.PRIME_DOC, BlurConstants.PRIME_DOC_VALUE);

  @SuppressWarnings("unchecked")
  public static <T extends Iface> T recordMethodCallsAndAverageTimes(final T t, Class<T> clazz) {
    final Map<String, Histogram> histogramMap = new ConcurrentHashMap<String, Histogram>();
    Method[] declaredMethods = Iface.class.getDeclaredMethods();
    for (Method m : declaredMethods) {
      String name = m.getName();
      histogramMap.put(name, Metrics.newHistogram(new MetricName(ORG_APACHE_BLUR, BLUR, name, THRIFT_CALLS)));
    }
    InvocationHandler handler = new InvocationHandler() {
      @Override
      public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        long start = System.nanoTime();
        try {
          return method.invoke(t, args);
        } catch (InvocationTargetException e) {
          throw e.getTargetException();
        } finally {
          long end = System.nanoTime();
          String name = method.getName();
          Histogram histogram = histogramMap.get(name);
          histogram.update((end - start) / 1000);
        }
      }
    };
    return (T) Proxy.newProxyInstance(clazz.getClassLoader(), new Class[] { clazz }, handler);
  }

  public static void setupZookeeper(ZooKeeper zookeeper) throws KeeperException, InterruptedException {
    setupZookeeper(zookeeper, null);
  }

  public synchronized static void setupZookeeper(ZooKeeper zookeeper, String cluster) throws KeeperException,
      InterruptedException {
    BlurUtil.createIfMissing(zookeeper, ZookeeperPathConstants.getBasePath());
    BlurUtil.createIfMissing(zookeeper, ZookeeperPathConstants.getOnlineControllersPath());
    BlurUtil.createIfMissing(zookeeper, ZookeeperPathConstants.getClustersPath());
    if (cluster != null) {
      BlurUtil.createIfMissing(zookeeper, ZookeeperPathConstants.getClusterPath(cluster));
      BlurUtil.createIfMissing(zookeeper, ZookeeperPathConstants.getSafemodePath(cluster));
      BlurUtil.createIfMissing(zookeeper, ZookeeperPathConstants.getRegisteredShardsPath(cluster));
      BlurUtil.createIfMissing(zookeeper, ZookeeperPathConstants.getOnlineShardsPath(cluster));
      BlurUtil.createIfMissing(zookeeper, ZookeeperPathConstants.getTablesPath(cluster));
    }
  }

  public static void createIfMissing(ZooKeeper zookeeper, String path) throws KeeperException, InterruptedException {
    if (zookeeper.exists(path, false) == null) {
      try {
        zookeeper.create(path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
      } catch (KeeperException e) {
        if (e.code() == Code.NODEEXISTS) {
          return;
        }
        throw e;
      }
    }
  }

  public static List<Long> getList(AtomicLongArray atomicLongArray) {
    if (atomicLongArray == null) {
      return null;
    }
    List<Long> counts = new ArrayList<Long>(atomicLongArray.length());
    for (int i = 0; i < atomicLongArray.length(); i++) {
      counts.add(atomicLongArray.get(i));
    }
    return counts;
  }

  public static void quietClose(Object... close) {
    if (close == null) {
      return;
    }
    for (Object object : close) {
      if (object != null) {
        close(object);
      }
    }
  }

  private static void close(Object object) {
    Class<? extends Object> clazz = object.getClass();
    Method method;
    try {
      method = clazz.getMethod("close", EMPTY_PARAMETER_TYPES);
    } catch (SecurityException e) {
      throw new RuntimeException(e);
    } catch (NoSuchMethodException e) {
      return;
    }
    try {
      method.invoke(object, EMPTY_OBJECT_ARRAY);
    } catch (Exception e) {
      LOG.error("Error while trying to close object [{0}]", e, object);
    }
  }

  public static byte[] toBytes(Serializable serializable) {
    try {
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      ObjectOutputStream stream = new ObjectOutputStream(outputStream);
      stream.writeObject(serializable);
      stream.close();
      return outputStream.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static Serializable fromBytes(byte[] bs) {
    ObjectInputStream stream = null;
    try {
      stream = new ObjectInputStream(new ByteArrayInputStream(bs));
      return (Serializable) stream.readObject();
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      if (stream != null) {
        try {
          stream.close();
        } catch (IOException e) {
          // eat
        }
      }
    }
  }

  public static List<Long> toList(AtomicLongArray atomicLongArray) {
    if (atomicLongArray == null) {
      return null;
    }
    int length = atomicLongArray.length();
    List<Long> result = new ArrayList<Long>(length);
    for (int i = 0; i < length; i++) {
      result.add(atomicLongArray.get(i));
    }
    return result;
  }

  public static AtomicLongArray getAtomicLongArraySameLengthAsList(List<?> list) {
    if (list == null) {
      return null;
    }
    return new AtomicLongArray(list.size());
  }

  public static BlurResults convertToHits(BlurResultIterable hitsIterable, BlurQuery query,
      AtomicLongArray facetCounts, ExecutorService executor, Selector selector, final Iface iface, final String table)
      throws InterruptedException, ExecutionException {
    BlurResults results = new BlurResults();
    results.setTotalResults(hitsIterable.getTotalResults());
    results.setShardInfo(hitsIterable.getShardInfo());
    if (query.minimumNumberOfResults > 0) {
      hitsIterable.skipTo(query.start);
      int count = 0;
      Iterator<BlurResult> iterator = hitsIterable.iterator();
      while (iterator.hasNext() && count < query.fetch) {
        results.addToResults(iterator.next());
        count++;
      }
    }
    if (results.results == null) {
      results.results = new ArrayList<BlurResult>();
    }
    if (facetCounts != null) {
      results.facetCounts = BlurUtil.toList(facetCounts);
    }
    if (selector != null) {
      List<Future<FetchResult>> futures = new ArrayList<Future<FetchResult>>();
      for (int i = 0; i < results.results.size(); i++) {
        BlurResult result = results.results.get(i);
        final Selector s = new Selector(selector);
        s.setLocationId(result.locationId);
        futures.add(executor.submit(new Callable<FetchResult>() {
          @Override
          public FetchResult call() throws Exception {
            return iface.fetchRow(table, s);
          }
        }));
      }
      for (int i = 0; i < results.results.size(); i++) {
        Future<FetchResult> future = futures.get(i);
        BlurResult result = results.results.get(i);
        result.setFetchResult(future.get());
      }
    }
    results.query = query;
    results.query.selector = selector;
    return results;
  }

  public static void setStartTime(BlurQuery query) {
    if (query.startTime == 0) {
      query.startTime = System.currentTimeMillis();
    }
  }

  public static String getVersion() {
    String path = "/META-INF/maven/org.apache.blur/blur-core/pom.properties";
    InputStream inputStream = BlurUtil.class.getResourceAsStream(path);
    if (inputStream == null) {
      return UNKNOWN;
    }
    Properties prop = new Properties();
    try {
      prop.load(inputStream);
    } catch (IOException e) {
      LOG.error("Unknown error while getting version.", e);
      return UNKNOWN;
    }
    Object verison = prop.get("version");
    if (verison == null) {
      return UNKNOWN;
    }
    return verison.toString();
  }

  public static void unlockForSafeMode(ZooKeeper zookeeper, String lockPath) throws InterruptedException,
      KeeperException {
    zookeeper.delete(lockPath, -1);
    LOG.info("Lock released.");
  }

  public static String lockForSafeMode(ZooKeeper zookeeper, String nodeName, String cluster) throws KeeperException,
      InterruptedException {
    LOG.info("Getting safe mode lock.");
    final Object lock = new Object();
    String blurSafemodePath = ZookeeperPathConstants.getSafemodePath(cluster);
    String newPath = zookeeper.create(blurSafemodePath + "/safemode-", nodeName.getBytes(), Ids.OPEN_ACL_UNSAFE,
        CreateMode.EPHEMERAL_SEQUENTIAL);
    Watcher watcher = new Watcher() {
      @Override
      public void process(WatchedEvent event) {
        synchronized (lock) {
          lock.notifyAll();
        }
      }
    };
    while (true) {
      synchronized (lock) {
        List<String> children = new ArrayList<String>(zookeeper.getChildren(blurSafemodePath, watcher));
        Collections.sort(children);
        if (newPath.equals(blurSafemodePath + "/" + children.get(0))) {
          LOG.info("Lock aquired.");
          return newPath;
        } else {
          lock.wait(BlurConstants.ZK_WAIT_TIME);
        }
      }
    }
  }

  public static String getShardName(int id) {
    return getShardName(BlurConstants.SHARD_PREFIX, id);
  }

  public static String getShardName(String prefix, int id) {
    return prefix + buffer(id, 8);
  }

  private static String buffer(int value, int length) {
    String str = Integer.toString(value);
    while (str.length() < length) {
      str = "0" + str;
    }
    return str;
  }

  public static String humanizeTime(long time, TimeUnit unit) {
    long seconds = unit.toSeconds(time);
    long hours = getHours(seconds);
    seconds = seconds - TimeUnit.HOURS.toSeconds(hours);
    long minutes = getMinutes(seconds);
    seconds = seconds - TimeUnit.MINUTES.toSeconds(minutes);
    return humanizeTime(hours, minutes, seconds);
  }

  public static String humanizeTime(long hours, long minutes, long seconds) {
    StringBuilder builder = new StringBuilder();
    if (hours == 0 && minutes != 0) {
      addMinutes(builder, minutes);
    } else if (hours != 0) {
      addHours(builder, hours);
      addMinutes(builder, minutes);
    }
    addSeconds(builder, seconds);
    return builder.toString().trim();
  }

  private static void addHours(StringBuilder builder, long hours) {
    builder.append(hours).append(" hours ");
  }

  private static void addMinutes(StringBuilder builder, long minutes) {
    builder.append(minutes).append(" minutes ");
  }

  private static void addSeconds(StringBuilder builder, long seconds) {
    builder.append(seconds).append(" seconds ");
  }

  private static long getMinutes(long seconds) {
    return seconds / TimeUnit.MINUTES.toSeconds(1);
  }

  private static long getHours(long seconds) {
    return seconds / TimeUnit.HOURS.toSeconds(1);
  }

  public static long getMemoryUsage(IndexReader r) {
    long sizeOf = RamUsageEstimator.sizeOf(r);
    return sizeOf;
  }

  public static void createPath(ZooKeeper zookeeper, String path, byte[] data) throws KeeperException,
      InterruptedException {
    zookeeper.create(path, data, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
  }

  public static void setupFileSystem(String uri, int shardCount) throws IOException {
    Path tablePath = new Path(uri);
    FileSystem fileSystem = FileSystem.get(tablePath.toUri(), new Configuration());
    if (createPath(fileSystem, tablePath)) {
      LOG.info("Table uri existed.");
      validateShardCount(shardCount, fileSystem, tablePath);
    }
    for (int i = 0; i < shardCount; i++) {
      String shardName = BlurUtil.getShardName(SHARD_PREFIX, i);
      Path shardPath = new Path(tablePath, shardName);
      createPath(fileSystem, shardPath);
    }
  }

  public static void validateShardCount(int shardCount, FileSystem fileSystem, Path tablePath) throws IOException {
    // Check that all the directories that should be are in fact there.
    for (int i = 0; i < shardCount; i++) {
      Path path = new Path(tablePath, BlurUtil.getShardName(BlurConstants.SHARD_PREFIX, i));
      if (!fileSystem.exists(path)) {
        LOG.error("Path [{0}] for shard [{1}] does not exist.", path, i);
        throw new RuntimeException("Path [" + path + "] for shard [" + i + "] does not exist.");
      }
    }

    FileStatus[] listStatus = fileSystem.listStatus(tablePath);
    for (FileStatus fs : listStatus) {
      Path path = fs.getPath();
      String name = path.getName();
      if (name.startsWith(SHARD_PREFIX)) {
        int index = name.indexOf('-');
        String shardIndexStr = name.substring(index + 1);
        int shardIndex = Integer.parseInt(shardIndexStr);
        if (shardIndex >= shardCount) {
          LOG.error("Number of directories in table path [" + path + "] exceeds definition of [" + shardCount
              + "] shard count.");
          throw new RuntimeException("Number of directories in table path [" + path + "] exceeds definition of ["
              + shardCount + "] shard count.");
        }
      }
    }
  }

  public static boolean createPath(FileSystem fileSystem, Path path) throws IOException {
    if (!fileSystem.exists(path)) {
      LOG.info("Path [{0}] does not exist, creating.", path);
      fileSystem.mkdirs(path);
      return false;
    }
    return true;
  }

  public static int zeroCheck(int i, String message) {
    if (i < 1) {
      throw new RuntimeException(message);
    }
    return i;
  }

  public static <T> T nullCheck(T t, String message) {
    if (t == null) {
      throw new NullPointerException(message);
    }
    return t;
  }

  @SuppressWarnings("unchecked")
  public static <T> T getInstance(String className, Class<T> c) {
    Class<?> clazz;
    try {
      clazz = Class.forName(className);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    try {
      return (T) configure(clazz.newInstance());
    } catch (InstantiationException e) {
      throw new RuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  public static <T> T configure(T t) {
    if (t instanceof Configurable) {
      Configurable configurable = (Configurable) t;
      configurable.setConf(new Configuration());
    }
    return t;
  }

  public static byte[] read(TBase<?, ?> base) {
    if (base == null) {
      return null;
    }
    TMemoryBuffer trans = new TMemoryBuffer(1024);
    TJSONProtocol protocol = new TJSONProtocol(trans);
    try {
      base.write(protocol);
    } catch (TException e) {
      throw new RuntimeException(e);
    }
    trans.close();
    byte[] buf = new byte[trans.length()];
    System.arraycopy(trans.getArray(), 0, buf, 0, trans.length());
    return buf;
  }

  public static void write(byte[] data, TBase<?, ?> base) {
    nullCheck(null, "Data cannot be null.");
    TMemoryBuffer trans = new TMemoryBuffer(1024);
    TJSONProtocol protocol = new TJSONProtocol(trans);
    try {
      trans.write(data);
      base.read(protocol);
    } catch (TException e) {
      throw new RuntimeException(e);
    }
    trans.close();
  }

  public static void removeAll(ZooKeeper zooKeeper, String path) throws KeeperException, InterruptedException {
    List<String> list = zooKeeper.getChildren(path, false);
    for (String p : list) {
      removeAll(zooKeeper, path + "/" + p);
    }
    LOG.info("Removing path [{0}]", path);
    zooKeeper.delete(path, -1);
  }

  public static void removeIndexFiles(String uri) throws IOException {
    Path tablePath = new Path(uri);
    FileSystem fileSystem = FileSystem.get(tablePath.toUri(), new Configuration());
    fileSystem.delete(tablePath, true);
  }

  public static RowMutation toRowMutation(String table, Row row) {
    RowMutation rowMutation = new RowMutation();
    rowMutation.setRowId(row.getId());
    rowMutation.setTable(table);
    rowMutation.setRowMutationType(RowMutationType.REPLACE_ROW);
    List<Record> records = row.getRecords();
    for (Record record : records) {
      rowMutation.addToRecordMutations(toRecordMutation(record));
    }
    return rowMutation;
  }

  public static RecordMutation toRecordMutation(Record record) {
    RecordMutation recordMutation = new RecordMutation();
    recordMutation.setRecord(record);
    recordMutation.setRecordMutationType(RecordMutationType.REPLACE_ENTIRE_RECORD);
    return recordMutation;
  }

  public static int countDocuments(IndexReader reader, Term term) throws IOException {
    TermQuery query = new TermQuery(term);
    IndexSearcher indexSearcher = new IndexSearcher(reader);
    TopDocs topDocs = indexSearcher.search(query, 1);
    return topDocs.totalHits;
  }

  /**
   * NOTE: This is a potentially dangerous call, it will return all the
   * documents that match the term.
   * 
   * @param selector
   * 
   * @throws IOException
   */
  public static List<Document> fetchDocuments(IndexReader reader, Term term,
      ResetableDocumentStoredFieldVisitor fieldSelector, Selector selector) throws IOException {
    IndexSearcher indexSearcher = new IndexSearcher(reader);
    int docFreq = reader.docFreq(term);
    BooleanQuery booleanQueryForFamily = null;
    BooleanQuery booleanQuery = null;
    if (selector.getColumnFamiliesToFetchSize() > 0) {
      booleanQueryForFamily = new BooleanQuery();
      for (String familyName : selector.getColumnFamiliesToFetch()) {
        booleanQueryForFamily
            .add(new TermQuery(new Term(BlurConstants.FAMILY, familyName)), BooleanClause.Occur.SHOULD);
      }
      booleanQuery = new BooleanQuery();
      booleanQuery.add(new TermQuery(term), BooleanClause.Occur.MUST);
      booleanQuery.add(booleanQueryForFamily, BooleanClause.Occur.MUST);
    }
    Query query = booleanQuery == null ? new TermQuery(term) : booleanQuery;
    TopDocs topDocs = indexSearcher.search(query, docFreq);
    int totalHits = topDocs.totalHits;
    List<Document> docs = new ArrayList<Document>();

    int start = selector.getStartRecord();
    int end = selector.getMaxRecordsToFetch() + start;

    for (int i = start; i < end; i++) {
      if (i >= totalHits) {
        break;
      }
      int doc = topDocs.scoreDocs[i].doc;
      indexSearcher.doc(doc, fieldSelector);
      docs.add(fieldSelector.getDocument());
      fieldSelector.reset();
    }
    return docs;
  }

  public static AtomicReader getAtomicReader(IndexReader reader) throws IOException {
    return SlowCompositeReaderWrapper.wrap(reader);
  }

  public static int getShardIndex(String shard) {
    int index = shard.indexOf('-');
    return Integer.parseInt(shard.substring(index + 1));
  }

  public static void validateRowIdAndRecord(String rowId, Record record) {
    if (!validator.matcher(record.family).matches()) {
      throw new IllegalArgumentException("Invalid column family name [ " + record.family
          + " ]. It should contain only this pattern [A-Za-z0-9_-]");
    }

    for (Column column : record.getColumns()) {
      if (!validator.matcher(column.name).matches()) {
        throw new IllegalArgumentException("Invalid column name [ " + column.name
            + " ]. It should contain only this pattern [A-Za-z0-9_-]");
      }
    }
  }

  public static void validateTableName(String tableName) {
    if (!validator.matcher(tableName).matches()) {
      throw new IllegalArgumentException("Invalid table name [ " + tableName
          + " ]. It should contain only this pattern [A-Za-z0-9_-]");
    }
  }

  public static void validateShardName(String shardName) {
    if (!validator.matcher(shardName).matches()) {
      throw new IllegalArgumentException("Invalid shard name [ " + shardName
          + " ]. It should contain only this pattern [A-Za-z0-9_-]");
    }
  }

  public static String getPid() {
    return ManagementFactory.getRuntimeMXBean().getName();
  }
}