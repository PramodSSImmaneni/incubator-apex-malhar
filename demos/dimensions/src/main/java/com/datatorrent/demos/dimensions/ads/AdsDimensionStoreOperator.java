/*
 * Copyright (c) 2014 DataTorrent, Inc. ALL Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datatorrent.demos.dimensions.ads;

import com.datatorrent.api.Context.OperatorContext;
import com.datatorrent.api.DefaultInputPort;
import com.datatorrent.api.DefaultOutputPort;
import com.datatorrent.api.annotation.InputPortFieldAnnotation;
import com.datatorrent.common.util.Slice;
import com.datatorrent.contrib.hdht.AbstractSinglePortHDHTWriter;
import com.datatorrent.demos.dimensions.ads.AdInfo.AdInfoAggregateEvent;
import com.datatorrent.demos.dimensions.ads.AdInfo.AdInfoAggregator;
import com.datatorrent.lib.appdata.qr.Query;
import com.datatorrent.lib.appdata.qr.QueryDeserializerFactory;
import com.datatorrent.lib.appdata.qr.Result;
import com.datatorrent.lib.appdata.qr.ResultSerializerFactory;
import com.datatorrent.lib.appdata.qr.processor.QueryComputer;
import com.datatorrent.lib.appdata.qr.processor.QueryProcessor;
import com.datatorrent.lib.appdata.qr.processor.WEQueryQueueManager;
import com.datatorrent.lib.appdata.schemas.SchemaQuery;
import com.datatorrent.lib.appdata.schemas.ads.AdsKeys;
import com.datatorrent.lib.appdata.schemas.ads.AdsOneTimeQuery;
import com.datatorrent.lib.appdata.schemas.ads.AdsOneTimeResult;
import com.datatorrent.lib.appdata.schemas.ads.AdsTimeRangeBucket;
import com.datatorrent.lib.appdata.schemas.ads.AdsUpdateQuery;
import com.datatorrent.lib.codec.KryoSerializableStreamCodec;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang.mutable.MutableBoolean;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AdsDimension Store Operator
 *
 * @displayName Dimensional Store
 * @category Store
 * @tags storage, hdfs, dimensions, hdht
 *
 * @since 2.0.0
 */
public class AdsDimensionStoreOperator extends AbstractSinglePortHDHTWriter<AdInfoAggregateEvent>
{
  private static final Logger LOG = LoggerFactory.getLogger(AdsDimensionStoreOperator.class);

  public final transient DefaultOutputPort<String> queryResult = new DefaultOutputPort<String>();

  @InputPortFieldAnnotation(optional=true)
  public transient final DefaultInputPort<String> query = new DefaultInputPort<String>()
  {
    @Override public void process(String s)
    {
      Query query = queryDeserializerFactory.deserialize(s);

      //Query was not parseable
      if(query == null) {
        return;
      }

      if(query instanceof SchemaQuery) {
        //TODO emit schema
      }
      else if(query instanceof AdsUpdateQuery) {
        throw new UnsupportedOperationException("The " + AdsUpdateQuery.class +
                                                " query is not supported now.");
      }
      else if(query instanceof AdsOneTimeQuery) {
        queryProcessor.enqueue((AdsOneTimeQuery) query, null, null);
      }
    }
  };

  private transient final ByteBuffer valbb = ByteBuffer.allocate(8 * 4);
  private transient final ByteBuffer keybb = ByteBuffer.allocate(8 + 4 * 3);
  protected boolean debug = false;

  protected static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS");
  // in-memory aggregation before hitting WAL
  protected final SortedMap<Long, Map<AdInfoAggregateEvent, AdInfoAggregateEvent>> cache = Maps.newTreeMap();
  // TODO: should be aggregation interval count
  private int maxCacheSize = 5;

  private AdInfoAggregator aggregator;

  @VisibleForTesting
  protected transient final Map<String, TimeSeriesQuery> timeSeriesQueries = Maps.newConcurrentMap();
  private transient ObjectMapper mapper = null;
  private long defaultTimeWindow = TimeUnit.MILLISECONDS.convert(20, TimeUnit.MINUTES);

  //==========================================================================
  // Query Processing - Start
  //==========================================================================

  private QueryProcessor<AdsOneTimeQuery, AdsQueryMeta, Long, MutableBoolean> queryProcessor;
  @SuppressWarnings("unchecked")
  private QueryDeserializerFactory queryDeserializerFactory;
  private ResultSerializerFactory resultSerializerFactory;
  private static final Long QUERY_QUEUE_WINDOW_COUNT = 30L;
  private static final int QUERY_QUEUE_WINDOW_COUNT_INT = (int) ((long) QUERY_QUEUE_WINDOW_COUNT);
  private static final TimeUnit TIME_UNIT = TimeUnit.MILLISECONDS;

  //==========================================================================
  // Query Processing - End
  //==========================================================================

  public int getMaxCacheSize()
  {
    return maxCacheSize;
  }

  public void setMaxCacheSize(int maxCacheSize)
  {
    this.maxCacheSize = maxCacheSize;
  }

  public long getDefaultTimeWindow()
  {
    return defaultTimeWindow;
  }

  public void setDefaultTimeWindow(long defaultTimeWindow)
  {
    this.defaultTimeWindow = defaultTimeWindow;
  }

  public AdInfoAggregator getAggregator()
  {
    return aggregator;
  }

  public void setAggregator(AdInfoAggregator aggregator)
  {
    this.aggregator = aggregator;
  }

  public boolean isDebug()
  {
    return debug;
  }

  public void setDebug(boolean debug)
  {
    this.debug = debug;
  }

  /**
   * Perform aggregation in memory until HDHT flush threshold is reached.
   * Avoids WAL writes for most of the aggregation.
   */
  @Override
  protected void processEvent(AdInfoAggregateEvent event) throws IOException
  {
    Map<AdInfoAggregateEvent, AdInfoAggregateEvent> valMap = cache.get(event.getTimestamp());
    if (valMap == null) {
      valMap = new HashMap<AdInfoAggregateEvent, AdInfoAggregateEvent>();
      valMap.put(event, event);
      cache.put(event.getTimestamp(), valMap);
    } else {
      AdInfoAggregateEvent val = valMap.get(event);
      // FIXME: lookup from store as the value may have been flushed previously.
      if (val == null) {
        valMap.put(event, event);
        return;
      } else {
        aggregator.aggregate(val, event);
      }
    }
  }

  @Override
  protected HDHTCodec<AdInfoAggregateEvent> getCodec()
  {
    return new AdInfoAggregateCodec();
  }

  @Override
  public void setup(OperatorContext context)
  {
    super.setup(context);

    //Setup for query processing
    queryProcessor =
    new QueryProcessor<AdsOneTimeQuery, AdsQueryMeta, Long, MutableBoolean>(null,
                                                  new AdsWEQueryQueueManager(this),
                                                  null);
    queryDeserializerFactory = new QueryDeserializerFactory(SchemaQuery.class,
                                                            AdsUpdateQuery.class,
                                                            AdsOneTimeQuery.class);
    resultSerializerFactory = new ResultSerializerFactory();

    queryProcessor.setup(context);
  }

  @Override
  public void beginWindow(long windowId)
  {
    queryProcessor.beginWindow(windowId);
    super.beginWindow(windowId);
  }

  @Override
  public void endWindow()
  {
    // flush final aggregates
    int expiredEntries = cache.size() - maxCacheSize;
    while(expiredEntries-- > 0){

      Map<AdInfoAggregateEvent, AdInfoAggregateEvent> vals = cache.remove(cache.firstKey());
      for (Entry<AdInfoAggregateEvent, AdInfoAggregateEvent> en : vals.entrySet()) {
        AdInfoAggregateEvent ai = en.getValue();
        try {
          put(getBucketKey(ai), new Slice(getKey(ai)), getValue(ai));
        } catch (IOException e) {
          LOG.warn("Error putting the value", e);
        }
      }
    }

    //Process queries

    MutableBoolean done = new MutableBoolean(false);

    while(done.isFalse()) {
      AdsOneTimeResult aotr = (AdsOneTimeResult) queryProcessor.process(done);

      if(aotr != null) {
        queryResult.emit(resultSerializerFactory.serialize(aotr));
      }
    }

    queryProcessor.endWindow();
    super.endWindow();
  }

  @Override
  public void teardown()
  {
    queryProcessor.teardown();
    super.teardown();
  }

  protected byte[] getKey(AdInfo event)
  {
    if (debug) {
      StringBuilder keyBuilder = new StringBuilder(32);
      keyBuilder.append(sdf.format(new Date(event.timestamp)));
      keyBuilder.append("|publisherId:").append(event.publisherId);
      keyBuilder.append("|advertiserId:").append(event.advertiserId);
      keyBuilder.append("|adUnit:").append(event.adUnit);
      return keyBuilder.toString().getBytes();
    }

    byte[] data = new byte[8 + 4 * 3];
    keybb.rewind();
    keybb.putLong(event.getTimestamp());
    keybb.putInt(event.getPublisherId());
    keybb.putInt(event.getAdvertiserId());
    keybb.putInt(event.getAdUnit());
    keybb.rewind();
    keybb.get(data);
    return data;
  }

  protected byte[] getValue(AdInfoAggregateEvent event)
  {
    if (debug) {
      StringBuilder keyBuilder = new StringBuilder(32);
      keyBuilder.append("|clicks:").append(event.clicks);
      keyBuilder.append("|cost:").append(event.cost);
      keyBuilder.append("|impressions:").append(event.impressions);
      keyBuilder.append("|revenue:").append(event.revenue);
      return keyBuilder.toString().getBytes();
    }
    byte[] data = new byte[8 * 4];
    valbb.rewind();
    valbb.putLong(event.clicks);
    valbb.putDouble(event.cost);
    valbb.putLong(event.impressions);
    valbb.putDouble(event.revenue);
    valbb.rewind();
    valbb.get(data);
    return data;
  }

  private AdInfo.AdInfoAggregateEvent getAggregateFromString(String key, String value)
  {
    AdInfo.AdInfoAggregateEvent ae = new AdInfo.AdInfoAggregateEvent();
    Pattern p = Pattern.compile("([^|]*)\\|publisherId:(\\d+)\\|advertiserId:(\\d+)\\|adUnit:(\\d+)");
    Matcher m = p.matcher(key);
    m.find();
    try {
      Date date = sdf.parse(m.group(1));
      ae.timestamp = date.getTime();
    } catch (Exception ex) {
      ae.timestamp = 0;
    }
    ae.publisherId = Integer.valueOf(m.group(2));
    ae.advertiserId = Integer.valueOf(m.group(3));
    ae.adUnit = Integer.valueOf(m.group(4));

    p = Pattern.compile("\\|clicks:(.*)\\|cost:(.*)\\|impressions:(.*)\\|revenue:(.*)");
    m = p.matcher(value);
    m.find();
    ae.clicks = Long.valueOf(m.group(1));
    ae.cost = Double.valueOf(m.group(2));
    ae.impressions = Long.valueOf(m.group(3));
    ae.revenue = Double.valueOf(m.group(4));
    return ae;
  }

  protected AdInfo.AdInfoAggregateEvent bytesToAggregate(Slice key, byte[] value)
  {
    if (key == null || value == null)
      return null;

    AdInfo.AdInfoAggregateEvent ae = new AdInfo.AdInfoAggregateEvent();
    if (debug) {
      return getAggregateFromString(new String(key.buffer, key.offset, key.length), new String(value));
    }

    java.nio.ByteBuffer bb = ByteBuffer.wrap(key.buffer, key.offset, key.length);
    ae.timestamp = bb.getLong();
    ae.publisherId = bb.getInt();
    ae.advertiserId = bb.getInt();
    ae.adUnit = bb.getInt();

    bb = ByteBuffer.wrap(value);
    ae.clicks = bb.getLong();
    ae.cost = bb.getDouble();
    ae.impressions = bb.getLong();
    ae.revenue = bb.getDouble();
    return ae;
  }

  public static class AdInfoAggregateCodec extends KryoSerializableStreamCodec<AdInfoAggregateEvent> implements HDHTCodec<AdInfoAggregateEvent>
  {
    public AdsDimensionStoreOperator operator;

    @Override
    public byte[] getKeyBytes(AdInfoAggregateEvent aggr)
    {
      return operator.getKey(aggr);
    }

    @Override
    public byte[] getValueBytes(AdInfoAggregateEvent aggr)
    {
      return operator.getValue(aggr);
    }

    @Override
    public AdInfoAggregateEvent fromKeyValue(Slice key, byte[] value)
    {
      if (key == null || value == null)
        return null;

      AdInfo.AdInfoAggregateEvent ae = new AdInfo.AdInfoAggregateEvent();
      if (operator.debug) {
        return operator.getAggregateFromString(new String(key.buffer, key.offset, key.length), new String(value));
      }

      java.nio.ByteBuffer bb = ByteBuffer.wrap(key.buffer, key.offset, key.length);
      ae.timestamp = bb.getLong();
      ae.publisherId = bb.getInt();
      ae.advertiserId = bb.getInt();
      ae.adUnit = bb.getInt();

      bb = ByteBuffer.wrap(value);
      ae.clicks = bb.getLong();
      ae.cost = bb.getDouble();
      ae.impressions = bb.getLong();
      ae.revenue = bb.getDouble();
      return ae;
    }

    @Override
    public int getPartition(AdInfoAggregateEvent t)
    {
      final int prime = 31;
      int result = 1;
      result = prime * result + t.adUnit;
      result = prime * result + t.advertiserId;
      result = prime * result + t.publisherId;
      return result;
    }
        private static final long serialVersionUID = 201411031407L;

  }

  protected void processTimeSeriesQueries()
  {
    Iterator<Map.Entry<String, TimeSeriesQuery>> it = this.timeSeriesQueries.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, TimeSeriesQuery> e = it.next();
      TimeSeriesQuery rangeQuery = e.getValue();
      if (--rangeQuery.windowCountdown < 0) {
        LOG.debug("Removing expired query {}", rangeQuery);
        it.remove(); // query expired
        continue;
      }

      TimeSeriesQueryResult res = new TimeSeriesQueryResult();
      res.id = rangeQuery.id;
      res.countDown = rangeQuery.windowCountdown;
      res.data = Lists.newArrayListWithExpectedSize(rangeQuery.points.size());
      rangeQuery.prototype.timestamp = rangeQuery.startTime;
      for (HDSQuery query : rangeQuery.points) {
        // check in-flight memory store first
        Map<AdInfoAggregateEvent, AdInfoAggregateEvent> buffered = cache.get(rangeQuery.prototype.timestamp);
        if (buffered != null) {
          AdInfo.AdInfoAggregateEvent ae = buffered.get(rangeQuery.prototype);
          if (ae != null) {
            LOG.debug("Adding from aggregation buffer {}" + ae);
            res.data.add(ae);
            rangeQuery.prototype.timestamp += rangeQuery.intervalTimeUnit.toMillis(1);
            continue;
          }
        }
        // results from persistent store
        if (query.processed && query.result != null) {
          AdInfo.AdInfoAggregateEvent ae = super.codec.fromKeyValue(query.key, query.result);
          if (ae != null)
            res.data.add(ae);
        }
        rangeQuery.prototype.timestamp += TimeUnit.MINUTES.toMillis(1);
      }
      if (!res.data.isEmpty()) {
        LOG.debug("Emitting {} points for {}", res.data.size(), res.id);
        queryResult.emit(res.toString());
      }
    }
  }

  //==========================================================================
  // Query Processing Classes - Start
  //==========================================================================

  class AdsWEQueryQueueManager extends WEQueryQueueManager<AdsOneTimeQuery, AdsQueryMeta>
  {
    private AdsDimensionStoreOperator operator;

    public AdsWEQueryQueueManager(AdsDimensionStoreOperator operator)
    {
      this.operator = operator;
    }

    @Override
    public boolean enqueue(AdsOneTimeQuery query, AdsQueryMeta queryMeta, Long windowExpireCount)
    {
      AdInfo.AdInfoAggregateEvent ae = new AdInfo.AdInfoAggregateEvent();
      AdsKeys aks = query.getData().getKeys();

      ae.setTimestamp(query.getData().getTime().getFromLong());
      ae.adUnit = aks.getLocationId();
      ae.publisherId = aks.getPublisherId();
      ae.advertiserId = aks.getAdvertiserId();

      long bucketKey = getBucketKey(ae);
      if(!(operator.partitions == null || operator.partitions.contains((int)bucketKey))) {
        //LOG.debug("Ignoring query for bucket {} when this partition serves {}", bucketKey, super.partitions);
        return false;
      }

      AdsTimeRangeBucket atrb = query.getData().getTime();
      long endTime;

      if(atrb.getToLong() == 0) {
        endTime = System.currentTimeMillis();
        atrb.setToLong(endTime);
      }
      else {
        endTime = atrb.getToLong();
      }

      if(atrb.getFromLong() == 0) {
        atrb.setFromLong(endTime - ((long) defaultTimeWindow));
      }

      List<HDSQuery> hdsQueries = Lists.newArrayList();

      for(ae.timestamp = atrb.getFromLong();
          ae.timestamp <= endTime;
          ae.timestamp += TIME_UNIT.toMillis(1)) {
        Slice key = new Slice(getKey(ae));
        HDSQuery hdsQuery = operator.queries.get(key);

        if(hdsQuery == null) {
          hdsQuery = new HDSQuery();
          hdsQuery.bucketKey = bucketKey;
          hdsQuery.key = key;
          operator.addQuery(hdsQuery);
        }
        else {
          if(hdsQuery.result == null) {
            LOG.debug("Forcing refresh for {}", hdsQuery);
            hdsQuery.processed = false;
          }
        }

        hdsQuery.keepAliveCount = QUERY_QUEUE_WINDOW_COUNT_INT;
        hdsQueries.add(hdsQuery);
      }

      AdsQueryMeta aqm = new AdsQueryMeta();
      aqm.setAdInofAggregateEvent(ae);
      aqm.setHdsQueries(hdsQueries);

      return super.enqueue(query, aqm, QUERY_QUEUE_WINDOW_COUNT);
    }
  }

  class AdsQueryComputer implements QueryComputer<AdsOneTimeQuery, AdsQueryMeta, MutableBoolean>
  {
    private AdsDimensionStoreOperator operator;

    public AdsQueryComputer(AdsDimensionStoreOperator operator)
    {
      this.operator = operator;
    }

    @Override
    public Result processQuery(AdsOneTimeQuery query, AdsQueryMeta adsQueryMeta, MutableBoolean context)
    {
      AdsOneTimeResult aotqr = new AdsOneTimeResult(query);
      AdInfo.AdInfoAggregateEvent prototype = adsQueryMeta.getAdInofAggregateEvent();

      Iterator<HDSQuery> queryIt = adsQueryMeta.getHdsQueries().iterator();

      for(long timestamp = query.getData().getTime().getFromLong();
          queryIt.hasNext();
          timestamp += TimeUnit.MINUTES.toMillis(1))
      {
        HDSQuery hdsQuery = queryIt.next();
        prototype.setTimestamp(timestamp);

        Map<AdInfoAggregateEvent, AdInfoAggregateEvent> buffered = cache.get(timestamp);

        if(buffered != null) {
          AdInfo.AdInfoAggregateEvent ae = buffered.get(prototype);

          if(ae != null) {
            LOG.debug("Adding from aggregation buffer {}" + ae);
          }
        }

        if(hdsQuery.processed && hdsQuery.result != null) {
          AdInfo.AdInfoAggregateEvent ae = operator.codec.fromKeyValue(hdsQuery.key, hdsQuery.result);

          if(ae != null) {
            AdsOneTimeResult.AdsOneTimeData aotd = new AdsOneTimeResult.AdsOneTimeData();
            aotd.setTimeLong(ae.timestamp);
            aotd.setAdvertiserId(ae.advertiserId);
            aotd.setPublisherId(ae.publisherId);
            aotd.setLocationId(ae.adUnit);
            aotd.setImpressions(ae.impressions);
            aotd.setClicks(ae.clicks);
            aotd.setCost(ae.cost);
            aotd.setRevenue(ae.revenue);
            aotqr.getData().add(aotd);
          }
        }
      }

      if(aotqr.getData().isEmpty()) {
        return null;
      }

      return aotqr;
    }

    @Override
    public void queueDepleted(MutableBoolean context)
    {
      context.setValue(true);
    }
  }

  static class AdsQueryMeta
  {
    private List<HDSQuery> hdsQueries;
    private AdInfo.AdInfoAggregateEvent adInofAggregateEvent;

    public AdsQueryMeta()
    {
    }

    /**
     * @return the hdsQueries
     */
    public List<HDSQuery> getHdsQueries()
    {
      return hdsQueries;
    }

    /**
     * @param hdsQueries the hdsQueries to set
     */
    public void setHdsQueries(List<HDSQuery> hdsQueries)
    {
      this.hdsQueries = hdsQueries;
    }

    /**
     * @return the adInofAggregateEvent
     */
    public AdInfo.AdInfoAggregateEvent getAdInofAggregateEvent()
    {
      return adInofAggregateEvent;
    }

    /**
     * @param adInofAggregateEvent the adInofAggregateEvent to set
     */
    public void setAdInofAggregateEvent(AdInfo.AdInfoAggregateEvent adInofAggregateEvent)
    {
      this.adInofAggregateEvent = adInofAggregateEvent;
    }
  }

  //==========================================================================
  // Query Processing Classes - End
  //==========================================================================

  static class TimeSeriesQuery
  {
    public String id;
    public int windowCountdown;
    public  AdInfo.AdInfoAggregateEvent prototype;
    public long startTime;
    public long endTime;
    public TimeUnit intervalTimeUnit = TimeUnit.MINUTES;
    private transient LinkedHashSet<HDSQuery> points = Sets.newLinkedHashSet();

    @Override public String toString()
    {
      return "TimeSeriesQuery{" +
          "id='" + id + '\'' +
          ", windowCountdown=" + windowCountdown +
          ", startTime=" + startTime +
          ", endTime=" + endTime +
          '}';
    }
  }

  /**
   * Parameters for registration of query.
   */
  static class QueryParameters
  {
    public String id;
    public int numResults;
    public Map<String, String> keys;
    public long startTime;
    public long endTime;
  }

  static public class TimeSeriesQueryResult
  {
    public String id;
    public long countDown;
    public List<AdInfo.AdInfoAggregateEvent> data;
  }

}