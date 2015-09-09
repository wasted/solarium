// Copyright 2011-2012 Foursquare Labs Inc. All Rights Reserved.
// Copyright (c) 2014, 2015, wasted.io Ltd.

package io.wasted.solarium

import java.util
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ ExecutorService, Executors }

import com.fasterxml.jackson.annotation.{ JsonCreator, JsonProperty }
import com.fasterxml.jackson.databind.{ DeserializationFeature, ObjectMapper }
import com.twitter.conversions.time._
import com.twitter.util.{ Await, Duration, Future, FuturePool }
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http._
import io.netty.util.CharsetUtil
import io.wasted.solarium.Ast.{ Clause, Query, ScoreBoost, _ }
import io.wasted.util.Logger
import io.wasted.util.http._
import net.liftweb.common.{ Box, Empty, Full }
import net.liftweb.record.field.{ BooleanField, DoubleField, IntField, LongField, StringField }
import net.liftweb.record.{ Field, MetaRecord, OwnedField, Record }
import org.bson.types.ObjectId
import org.elasticsearch.action.search.{ SearchRequestBuilder, SearchResponse, SearchType }
import org.elasticsearch.client.Client
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.index.query.QueryBuilders.filteredQuery
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder
import org.elasticsearch.index.query.functionscore.script.ScriptScoreFunctionBuilder
import org.elasticsearch.index.query.{ AndFilterBuilder, BoostingQueryBuilder, FilterBuilder => ElasticFilterBuilder, QueryBuilder => ElasticQueryBuilder, QueryBuilders => EQueryBuilders }
import org.elasticsearch.node.Node
import org.elasticsearch.search.facet.FacetBuilder
import org.elasticsearch.search.facet.terms.TermsFacetBuilder
import org.elasticsearch.search.facet.terms.strings.InternalStringTermsFacet
import org.elasticsearch.search.sort.{ ScriptSortBuilder, SortOrder }
import org.joda.time.DateTime

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.xml.NodeSeq

/**
 * SolrResponseException class that extends RuntimeException
 */
case class SolrResponseException(code: Int, reason: String, solrName: String, query: String) extends RuntimeException {
  override def getMessage: String = {
    "Solr %s request resulted in HTTP %s: %s\n%s: %s"
      .format(solrName, code, reason, solrName, query)
  }
}

case class UnimplementedException(reason: String) extends RuntimeException {
  override def getMessage: String = {
    "Not implemented: %s".format(reason)
  }
}

/**
 * The response header. There are normally more fields in the response header we could extract, but
 * we don't at present.
 */
case class ResponseHeader @JsonCreator() (@JsonProperty("status") status: Int, @JsonProperty("QTime") QTime: Int)

object Response {
  type RawDoc = (Map[String, Any], Option[Map[String, util.ArrayList[String]]])
}

/**
 * The response itself.
 * The "docs" field is not type safe, you should use one of results or oids to access the results
 * Y is the type that we are extracting from the response (e.g. a case class)
 */
case class Response[T <: Record[T], Y](schema: T, creator: Option[Response.RawDoc => Y],
                                       numFound: Int, start: Int, docs: Array[Response.RawDoc],
                                       fallOff: Option[Double], min: Option[Int],
                                       fieldFacets: Map[String, Map[String, Int]]) {
  // Docs with high Lucene scores.
  val filteredDocs: Array[Response.RawDoc] = filterHighQuality(docs)

  /**
   * Gets a List[T] of docs returned from Lucene.
   */
  def results[RT <: Record[RT]](B: Record[RT]): List[RT] = {
    filteredDocs.map(fd => {
      val doc: Map[String, Any] = fd._1
      val matchingHighlights: Option[Map[String, util.ArrayList[String]]] = fd._2
      val q: RT = B.meta.createRecord
      doc.foreach { a =>
        val fname = a._1
        val value = a._2
        q.fieldByName(fname).map { field =>
          matchingHighlights match {
            case Some(mhl) if mhl contains fname =>
              field match {
                case f: SlashemField[_, _] => f.setHighlighted(mhl.get(fname).get.asScala.toList)
                case _ =>
              }
            case _ =>
          }
          field.setFromAny(value)
        }
      }
      q.asInstanceOf[RT]
    }).toList
  }

  /**
   * Collect results which are of a high enough lucene score to be relevant.
   * @param rawDocs List of Docs to be filtered
   * @see RawDoc
   */
  private def filterHighQuality(rawDocs: Array[Response.RawDoc]): Array[Response.RawDoc] = {
    (min, fallOff) match {
      case (Some(minR), Some(qualityFallOff)) =>
        val scores = {
          val scoreArr = rawDocs.map(rd => rd._1.get("score").map(_.asInstanceOf[Double]))
          scoreArr.toList.map {
            case Some(dubdub) => dubdub
            case None => 0.0
          }
        }
        val hqCount = countHighQuality(scores, scoreAcc = 0, lastScore = 0, count = 0,
          minR = minR, qualityFallOff = qualityFallOff,
          individualQualityFallOff = qualityFallOff * 1.1)
        rawDocs.take(hqCount)
      case _ => rawDocs
    }
  }

  /**
   * Counts the number of high quality results using scores
   * returned from lucene
   */
  @tailrec
  private def countHighQuality(scores: List[Double], scoreAcc: Double = 0,
                               lastScore: Double = 0, count: Int = 0,
                               minR: Int = 1, qualityFallOff: Double = 0,
                               individualQualityFallOff: Double = 0): Int = {
    val minScore = {
      val avgScore = scoreAcc / count
      val threshold1 = qualityFallOff * avgScore
      val threshold2 = individualQualityFallOff * lastScore
      scala.math.min(threshold1, threshold2)
    }
    scores match {
      case score :: rest if count < minR || score > minScore =>
        countHighQuality(rest, scoreAcc = scoreAcc + score, lastScore = score,
          count = count + 1, minR = minR, qualityFallOff = qualityFallOff,
          individualQualityFallOff = individualQualityFallOff)
      case score :: rest if scoreAcc == 0 => countHighQuality(rest, scoreAcc, lastScore, count + 1)
      case _ => count
    }
  }

  /** Return a list of the documents in a usable form */
  def results: List[T] = results(schema)
  /**
   * Return a list of results handled by the creator
   * Most commonly used for case class based queries
   */
  def processedResults: List[Y] = {
    creator match {
      case Some(func) => filteredDocs.map(func(_)).toList
      case None => Nil
    }
  }
  /** Special for extracting just ObjectIds without the overhead of record. */
  def oids: List[ObjectId] = {
    filteredDocs.map({ doc => doc._1.find(x => x._1 == "id").map(x => new ObjectId(x._2.toString)) }).toList.flatten
  }
  /**
   * Another special case for extracting just ObjectId & score Tuple2s.
   * Please think twice before using
   */
  def oidScorePair: List[(ObjectId, Double)] = {
    val oids = filteredDocs.map({ doc => doc._1.find(x => x._1 == "id").map(x => new ObjectId(x._2.toString)) }).toList.flatten
    val scores = filteredDocs.map({ doc => doc._1.find(x => x._1 == "score").map(x => x._2.asInstanceOf[Double]) }).toList.flatten
    oids zip scores
  }

}

/** The search results class, you are probably most interested in the contents of response */
case class SearchResults[T <: Record[T], Y](responseHeader: ResponseHeader,
                                            response: Response[T, Y])

/** This is the raw representation of the response from solr, you probably don't want to poke at it directly. */
case class RawResponse @JsonCreator() (@JsonProperty("numFound") numFound: Int, @JsonProperty("start") start: Int,
                                       @JsonProperty("docs") docs: Array[util.HashMap[String, Any]])

/** This is the raw representation of the response from solr, you probably don't want to poke at it directly. */
case class RawSearchResults @JsonCreator() (@JsonProperty("responseHeader") responseHeader: ResponseHeader,
                                            @JsonProperty("response") response: RawResponse,
                                            @JsonProperty("highlighting") highlighting: util.HashMap[String, util.HashMap[String, util.ArrayList[String]]],
                                            @JsonProperty("facet_counts") facetCounts: RawFacetCounts)

/** This is the raw rep of the facet counts */
case class RawFacetCounts @JsonCreator() (@JsonProperty("facet_fields") facetFields: util.HashMap[String, util.ArrayList[Object]])

/** Slashem MetaRecord */
trait SlashemMeta[T <: Record[T]] extends MetaRecord[T] {
  self: MetaRecord[T] with T =>
  var logger: SolrQueryLogger = DefaultQueryLogger
  //Default timeout
  val timeout = 2
}

/** Elastic Search MetaRecord */
trait ElasticMeta[T <: Record[T]] extends SlashemMeta[T] {
  self: MetaRecord[T] with T =>

  val clusterName = "testcluster" // Override me knthx
  val indexName = "testindex" // Override me too
  val docType = "slashemdoc"
  val useTransport = true // Override if you want to use transport client
  val sniffMode = false // Override if you want to use sniff mode
  def servers: List[String] = List() // Define if your going to use the transport client
  def serverInetSockets = servers.map(x => {
    val h = x.split(":")
    val s = h.head
    val p = h.last
    new InetSocketTransportAddress(s, p.toInt)
  })

  var node: Node = null
  //This is volatile for double check locking to work see http://jeremymanson.blogspot.com/2008/05/double-checked-locking.html
  //This requires JDK5 or later
  @volatile var myClient: Client = null
  val clientCreateLock: AnyRef = new Object()

  val executorService: ExecutorService = Executors.newCachedThreadPool()
  val executorServiceFuturePool: FuturePool = FuturePool(executorService)

  /** Create or get the MetaRecord's client */
  def client: Client = {
    //Double check locking (safe with new JDKs and the @volatile up above ^)
    if (myClient == null) {
      clientCreateLock.synchronized {
        if (myClient == null) {
          myClient =
            if (useTransport) {
              val settings = ImmutableSettings.settingsBuilder().put("cluster.name", clusterName).put("client.transport.sniff", sniffMode)
              val tc = new TransportClient(settings)
              serverInetSockets.map(tc.addTransportAddress(_))
              tc
            } else {
              node.client()
            }
          //When shut down the JVM we want to explicitly shut down our connections
          //so we are a well behaved client
          Runtime.getRuntime.addShutdownHook(new Thread() {
            override def run(): Unit = {
              myClient.close()
            }
          })
        }
      }
    }
    myClient
  }
}

/** Solr MetaRecord */
trait SolrMeta[T <: Record[T]] extends SlashemMeta[T] {
  self: MetaRecord[T] with T =>

  /**
   * The servers is a list used in round-robin for running solr read queries against.
   * It can just be one element if you wish
   */
  def servers: List[(String, Int)]

  /* Not using the primary core, set it here*/
  def core: Option[String] = None

  /* Need a more crazy query path? override */
  def queryPath: String = {
    core match {
      case None => "/solr/select/"
      case Some(x) => "/solr/%s/select".format(x)
    }
  }

  // The name is used to determine which props to use as well as for logging
  def solrName: String

  // Params for the client
  def solrTcpConnectTimeout: Duration = 10.seconds
  def solrTimeout: Duration = 30.seconds
  def solrKeepAlive: Boolean = true
  def solrRetries: Int = 1
  val requestCounter = new AtomicLong()
  val httpCodec = NettyHttpCodec[HttpRequest, FullHttpResponse]()
    .withDecompression(decompression = false)
  protected val clients = servers.map { server =>
    server._1 -> HttpClient[FullHttpResponse]()
      .withSpecifics(httpCodec)
      .withRequestTimeout(solrTimeout)
      .withTcpKeepAlive(solrKeepAlive)
      .withTcpConnectTimeout(solrTcpConnectTimeout)
      .withTcpNoDelay(tcpNoDelay = true)
      .withRetries(solrRetries)
      .connectTo(server._1, server._2)
  }

  /**
   * Gets a HTTP Client for this and returns the Hostname (for Host-Header) as a tuple
   * @return (Hostname, Client)
   */
  def getClient: (String, HttpClient[FullHttpResponse]) = clients((requestCounter.incrementAndGet() % clients.length).toInt)

  // This is used so the json extractor can do its job
  implicit val formats = net.liftweb.json.DefaultFormats
  val mapper = {
    val a = new ObjectMapper
    a.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    a
  }

  def extractFromResponse[Y](r: String, creator: Option[Response.RawDoc => Y],
                             fieldstofetch: List[String] = Nil, fallOf: Option[Double] = None,
                             min: Option[Int] = None, queryText: String): Future[SearchResults[T, Y]] = {
    def parseFacetCounts(a: List[Object]): List[(String, Int)] = {
      a match {
        case Nil => Nil
        case (x: String) :: (y: Integer) :: z => (x, y.toInt) :: parseFacetCounts(z)
        // Shouldn't happen, but fail silently for now
        case _ => Nil
      }
    }
    // This intentionally avoids lift extract as it is too slow for our use case.
    try {
      val rsr = mapper.readValue(r, classOf[RawSearchResults])
      // Take the raw search result and make the type templated search result.
      val rawDocs = rsr.response.docs
      val joinedDocs: Array[(Map[String, Any], Option[Map[String, util.ArrayList[String]]])] = rawDocs.map(jdoc => {
        val doc = jdoc.asScala
        val hl = if (doc.contains("id") && rsr.highlighting != null) {
          val scalaHl = rsr.highlighting.asScala
          val key = doc.get("id").get.toString
          scalaHl.get(key) match {
            case Some(v) => Some(v.asScala.toMap)
            case _ => None
          }
        } else {
          None
        }
        Tuple2(doc.toMap, hl)
      })
      val facetCounts = rsr.facetCounts

      val facets: Map[String, Map[String, Int]] = if (facetCounts != null) {
        facetCounts.facetFields.asScala.map(ffCountTuple2 => {
          (ffCountTuple2._1, parseFacetCounts(ffCountTuple2._2.asScala.toList).toMap)
        }).toMap
      } else {
        Map.empty
      }
      Future(SearchResults(rsr.responseHeader,
        Response(createRecord, creator, rsr.response.numFound,
          rsr.response.start, joinedDocs, fallOf, min, facets)))

    } catch {
      case e: Throwable => Future.exception(new Exception("An error occured while parsing solr result \"" + r +
        "\" from query (" + queryText + ")", e))
    }
  }

  def queryString(params: Seq[(String, String)]): QueryStringEncoder = {
    val qse = new QueryStringEncoder("")
    qse.addParam("wt", "json")
    params.foreach(x => {
      qse.addParam(x._1, x._2)
    })
    qse
  }

  def rawQueryFuture(params: Seq[(String, String)]): Future[String] = {
    // Ugly
    val qse = queryString(params ++ logger.queryIdToken().map("magicLoggingToken" -> _).toList)

    val bytes = qse.toString.replaceAll("^\\?", "").getBytes("UTF-8")
    val request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, queryPath, Unpooled.wrappedBuffer(bytes))
    val (host, client) = getClient
    request.headers.add(HttpHeaders.Names.HOST, host)
    request.headers.add(HttpHeaders.Names.CONTENT_TYPE, "application/x-www-form-urlencoded")
    request.headers.add(HttpHeaders.Names.CONTENT_LENGTH, bytes.length.toString)
    val uri = new java.net.URI(queryPath)
    client.write(uri, () => request).map { response =>
      val r = response.getStatus match {
        case HttpResponseStatus.OK => response.content().toString(CharsetUtil.UTF_8)
        case status => throw SolrResponseException(status.code, status.reasonPhrase, solrName, qse.toString)
      }
      response.release()
      r
    }
  }

}

/** Logging and Timing solr trait */
trait SolrQueryLogger {
  /**
   * provide a null call back for people that don't want to implement
   * onStartExecuteQuery
   */
  val noopCallback: () => Unit = () => ()
  /**
   * to instrument start and stop of query return a function that will be called
   * when the query finishes
   */
  def onStartExecuteQuery(name: String, msg: String): () => Unit = noopCallback

  def log(name: String, msg: String, time: Long): Unit

  def debug(msg: String): Unit

  // If this returns a string then it will be appended to the query
  // so you can use it to match your query logs with application
  // logs.
  def queryIdToken(): Option[String] = None

  //Log failure
  def failure(name: String, message: String, e: Throwable): Unit = {
  }
  //Log success
  def success(name: String): Unit = {
  }
  //Log the number of results
  def resultCount(name: String, count: Int): Unit = {
  }
}

object DefaultQueryLogger extends SolrQueryLogger with Logger {
  override val loggerName = "io.wasted.solarium.Logger"
  override def onStartExecuteQuery(name: String, msg: String): () => Unit = noopCallback
  override def log(name: String, msg: String, time: Long): Unit = info("%s: %s (%s ms)", name, msg, time)
  override def debug(msg: String): Unit = debug(msg)
  override def resultCount(name: String, count: Int): Unit = debug("%s: %s results", name, count)
}

//If you want any of the geo queries you will have to implement this
trait SolrGeoHash {
  def coverString(geoLat: Double, geoLong: Double, radiusInMeters: Int, maxCells: Int): Seq[String]
  def rectCoverString(topRight: (Double, Double), bottomLeft: (Double, Double), maxCells: Int = 0, minLevel: Int = 0, maxLevel: Int = 0): Seq[String]
  def maxCells: Int = 0
}
//Default geohash, does nothing.
object NoopSolrGeoHash extends SolrGeoHash {
  def coverString(geoLat: Double, geoLong: Double, radiusInMeters: Int, maxCells: Int): Seq[String] = List("pleaseUseaRealGeoHash", "thisIsForFunctionalityTests")
  def rectCoverString(topRight: (Double, Double), bottomLeft: (Double, Double), maxCells: Int = 0, minLevel: Int = 0, maxLevel: Int = 0): Seq[String] = List("pleaseUseaRealGeoHash", "thisIsForFunctionalityTests")
}

trait SlashemSchema[M <: Record[M]] extends Record[M] {
  self: M with Record[M] =>

  def meta: SlashemMeta[M]

  //Set me to something which collects timing if you want (hint: you do)
  var geohash: SolrGeoHash = NoopSolrGeoHash

  // fixme(jonshea) this should go somewhere else
  def timeFuture[T](someFuture: Future[T]): Future[(Long, T)] = {
    val startTime = System.currentTimeMillis
    someFuture.map(x => {
      val endTime = System.currentTimeMillis
      (endTime - startTime, x)
    })
  }

  def where[F](c: M => Clause[F]): QueryBuilder[M, Unordered, Unlimited, defaultMM, NoSelect, NoHighlighting, NoQualityFilter, NoMinimumFacetCount, Unlimited, NoScoreModifiers] = {
    QueryBuilder(self, c(self), filters = Nil, boostQueries = Nil, queryFields = Nil,
      phraseBoostFields = Nil, boostFields = Nil, start = None, limit = None,
      tieBreaker = None, sort = None, minimumMatch = None, queryType = None,
      fieldsToFetch = Nil, facetSettings = FacetSettings(facetFieldList = Nil,
        facetMinCount = None,
        facetLimit = None,
        facetQuery = List()),
      customScoreScript = None, hls = None, pt = None, hlFragSize = None, creator = None,
      fallOf = None, min = None)
  }
  def query[Ord, Lim, MM <: MinimumMatchType, Y, H <: Highlighting, Q <: QualityFilter, FC <: FacetCount, FLim, ST <: ScoreType](timeout: Duration, qb: QueryBuilder[M, Ord, Lim, MM, Y, H, Q, FC, FLim, ST]): SearchResults[M, Y]
  def queryFuture[Ord, Lim, MM <: MinimumMatchType, Y, H <: Highlighting, Q <: QualityFilter, FC <: FacetCount, FLim, ST <: ScoreType](qb: QueryBuilder[M, Ord, Lim, MM, Y, H, Q, FC, FLim, ST]): Future[SearchResults[M, Y]]
}

trait ElasticSchema[M <: Record[M]] extends SlashemSchema[M] {
  self: M with SlashemSchema[M] =>

  def meta: ElasticMeta[M]

  def query[Ord, Lim, MM <: MinimumMatchType, Y, H <: Highlighting, Q <: QualityFilter, FC <: FacetCount, FLim, ST <: ScoreType](timeout: Duration, qb: QueryBuilder[M, Ord, Lim, MM, Y, H, Q, FC, FLim, ST]): SearchResults[M, Y] = {
    Await.result(queryFuture(qb, Some(timeout)), timeout)
  }

  def queryFuture[Ord, Lim, MM <: MinimumMatchType, Y, H <: Highlighting, Q <: QualityFilter, FC <: FacetCount, FLim, ST <: ScoreType](qb: QueryBuilder[M, Ord, Lim, MM, Y, H, Q, FC, FLim, ST]): Future[SearchResults[M, Y]] = {
    elasticQueryFuture(qb, buildElasticQuery(qb), None)
  }
  /*
   * queryFuture constructs a future query
   * @qb: The query builder representing the query to be executed
   * @timeoutOpt: An option type that requests a server side timeout for the query
   */
  def queryFuture[Ord, Lim, MM <: MinimumMatchType, Y, H <: Highlighting, Q <: QualityFilter, FC <: FacetCount, FLim, ST <: ScoreType](qb: QueryBuilder[M, Ord, Lim, MM, Y, H, Q, FC, FLim, ST], timeoutOpt: Option[Duration]): Future[SearchResults[M, Y]] = {
    elasticQueryFuture(qb, buildElasticQuery(qb), timeoutOpt)
  }

  def elasticQueryFuture[Ord, Lim, MM <: MinimumMatchType, Y, H <: Highlighting, Q <: QualityFilter, FC <: FacetCount, FLim, ST <: ScoreType](qb: QueryBuilder[M, Ord, Lim, MM, Y, H, Q, FC, FLim, ST], query: ElasticQueryBuilder, timeoutOpt: Option[Duration]): Future[SearchResults[M, Y]] = {
    val queryName = "e" + meta.indexName + ".query"
    val queryText = query.toString

    val client = meta.client
    val from = qb.start.map(_.toInt).getOrElse(qb.DefaultStart)
    val limit = qb.limit.map(_.toInt).getOrElse(qb.DefaultLimit)
    val baseRequest: SearchRequestBuilder = client.prepareSearch(meta.indexName)
      .setQuery(query)
      .setFrom(from)
      .setSize(limit)
      .setSearchType(SearchType.QUERY_THEN_FETCH)
    val request = qb.sort match {
      case None => baseRequest
      //Handle sorting by fields quickly
      case Some(Tuple2(Field(fieldName), "asc")) => baseRequest.addSort(fieldName, SortOrder.ASC)
      case Some(Tuple2(Field(fieldName), "desc")) => baseRequest.addSort(fieldName, SortOrder.DESC)
      //Handle sorting by scripts in general
      case Some(Tuple2(sort, dir)) =>
        val (params, scriptSrc) = sort.elasticBoost()
        val paramNames = (1 to params.length).map("p" + _)
        val script = scriptSrc.format(paramNames: _*)
        val keyedParams = paramNames zip params
        val sortOrder = dir match {
          case "asc" => SortOrder.ASC
          case "desc" => SortOrder.DESC
          case _ => throw UnimplementedException("Unsupported order direction " + dir);
        }
        val sortBuilder = new ScriptSortBuilder(script, "number").order(sortOrder)
        keyedParams.foreach(p => { sortBuilder.param(p._1, p._2) })
        baseRequest.addSort(sortBuilder)
      case _ =>
        throw UnimplementedException("Unsupported order type " + qb.sort);
    }

    /* Set the server side timeout */
    val timeLimmitedRequest = timeoutOpt match {
      case Some(timeout) => request.setTimeout(TimeValue.timeValueMillis(timeout.inMillis))
      case _ => request
    }

    /* Add a facet to the request */
    val facetedRequest = qb.facetSettings.facetFieldList match {
      case Nil => timeLimmitedRequest
      case _ =>
        termFacetQuery(qb.facetSettings.facetFieldList, qb.facetSettings.facetLimit).foreach(timeLimmitedRequest.addFacet)
        timeLimmitedRequest
    }

    val requestText = facetedRequest.toString
    meta.logger.debug("Request & Query details " + requestText)

    val onEndExecuteFunction: () => Unit = meta.logger.onStartExecuteQuery(queryName, requestText)
    val esfp = meta.executorServiceFuturePool

    val searchResultsFuture = esfp {
      try {
        val response: SearchResponse = facetedRequest.execute().get()
        response
      } finally {
        onEndExecuteFunction()
      }
    }

    timeFuture(searchResultsFuture).map({
      case (queryTime, result) =>
        meta.logger.log(queryName, requestText, queryTime)
        result
    }).map({
      response =>
        meta.logger.debug("Search response " + response.toString)
        val results = constructSearchResults(qb.creator,
          qb.start.map(_.toInt).getOrElse(qb.DefaultStart),
          qb.fallOf,
          qb.min,
          response)

        results
    })
      .onSuccess((v: SearchResults[M, Y]) => {
        meta.logger.success("e" + meta.indexName)
        meta.logger.resultCount("e" + meta.indexName, v.response.numFound)
      })
      .onFailure(e => meta.logger.failure("e" + meta.indexName, queryText, e))

  }

  def constructSearchResults[Y](creator: Option[Response.RawDoc => Y],
                                start: Int,
                                fallOff: Option[Double],
                                min: Option[Int],
                                response: SearchResponse): SearchResults[M, Y] = {
    val time = response.getTookInMillis
    val hitCount = response.getHits.totalHits().toInt
    val esHits = response.getHits.getHits
    val docs: Array[(Map[String, Any], Option[Map[String, java.util.ArrayList[String]]])] = esHits.map(doc => {
      val m = doc.sourceAsMap()
      val scoreDouble = doc.score().toDouble
      val annotedMap = (m.asScala ++ List("score" -> scoreDouble)).toMap
      val hlf = doc.getHighlightFields
      if (hlf == null) {
        Tuple2(annotedMap, None)
      } else {
        Tuple2(annotedMap,
          Some(doc.getHighlightFields.asScala
            .mapValues(v => {
              val fragments = v.getFragments
              new util.ArrayList(util.Arrays.asList(fragments.toString))
            })
            .toMap))
      }
    })

    val fieldFacet: Map[String, Map[String, Int]] = {
      val facets = response.getFacets
      if (facets != null) {
        facets.facets().asScala.filter(_.getType == "terms").
          map(f => f.asInstanceOf[InternalStringTermsFacet]).
          map(f => f.getName -> f.getEntries.asScala.map(t => t.getTerm.toString -> t.getCount).toMap).toMap
      } else {
        Map.empty
      }
    }

    SearchResults(ResponseHeader(200, time.toInt),
      Response(this, creator, hitCount, start, docs,
        fallOff = fallOff, min = min, fieldFacet))
  }

  def buildElasticQuery[Ord, Lim, MM <: MinimumMatchType, Y, H <: Highlighting, Q <: QualityFilter, FC <: FacetCount, FLim, ST <: ScoreType](qb: QueryBuilder[M, Ord, Lim, MM, Y, H, Q, FC, FLim, ST]): ElasticQueryBuilder = {
    val baseQuery: ElasticQueryBuilder = qb.clauses.elasticExtend(qb.queryFields,
      qb.phraseBoostFields,
      qb.minimumMatch)
    //Apply filters if necessary
    val fq = qb.filters match {
      case Nil => baseQuery
      case _ => filteredQuery(baseQuery, combineFilters(qb.filters.map(_.elasticFilter(qb.queryFields))))
    }
    //Apply any custom scoring rules (aka emulating Solr's bq/bf)
    val scoredQuery = qb.boostFields match {
      case Nil => qb.customScoreScript match {
        case Some((script, params)) => scoreWithScript(fq, script, params)
        case None => fq
      }
      case _ => scoreFields(fq, qb.boostFields)
    }
    //Apply query boosting
    val boostedQuery = qb.boostQueries match {
      case (x :: xs) => boostQueries(scoredQuery, qb)
      case _ => scoredQuery
    }
    boostedQuery
  }

  def termFacetQuery(facetFields: List[Ast.Field], facetLimit: Option[Int]): List[FacetBuilder] = {
    val fieldNames = facetFields.map(_.boost())
    val facetQueries = fieldNames.map(name => {
      val q = new TermsFacetBuilder(name).field(name)
      facetLimit match {
        case Some(c) =>
          q.size(c)
        case _ => q
      }
    })
    facetQueries
  }

  /**
   * Applies positive and negative query boosts
   */
  def boostQueries(query: ElasticQueryBuilder, qb: QueryBuilder[_, _, _, _, _, _, _, _, _, _]): ElasticQueryBuilder = {
    //Only bother making boost queries if we have negative boost queries otherwise we just append it
    val boostQueries = qb.boostQueries
    val negativeQueries = boostQueries.filter {
      case Clause(_, _, false) => true
      case _ => false
    }
    if (negativeQueries.nonEmpty) {
      val boostedQuery = new BoostingQueryBuilder()
      boostedQuery.positive(query)
      boostedQuery.negative(query)
      boostQueries.map {
        case q @ Clause(_, _, false) =>
          boostedQuery.negative(q.elasticExtend(qb.queryFields,
            qb.phraseBoostFields,
            qb.minimumMatch))
        case q =>
          boostedQuery.positive(q.elasticExtend(qb.queryFields,
            qb.phraseBoostFields,
            qb.minimumMatch))
      }
      boostedQuery.negativeBoost(0.1.toFloat)
      boostedQuery
    } else {
      val boolQuery = EQueryBuilders.boolQuery
      boolQuery.must(query)
      boostQueries.map(q => boolQuery.should(q.elasticExtend(qb.queryFields,
        qb.phraseBoostFields,
        qb.minimumMatch)))
      boolQuery
    }
  }

  /**
   * Custom score the fields which have scoreboosts
   */
  def scoreFields(query: ElasticQueryBuilder, fieldsToScore: List[ScoreBoost]): ElasticQueryBuilder = {
    val scoredFields = fieldsToScore.map(_.elasticBoost())
    val params = scoredFields.flatMap(_._1)
    val scriptSrc = scoredFields.map(_._2).mkString(" * ")
    val paramNames = (1 to params.length).map("p" + _)
    val script = scriptSrc.format(paramNames: _*)
    val namesAndParams = paramNames.zip(params).toMap
    //The ES client library breaks badly with low score values
    val scoreScript = "1.0 + _score * " + script + ""
    scoreWithScript(query, scoreScript, namesAndParams, native = false)
  }

  /**
   * Add the provided script and its params to the query and build a
   * CustomScoreQuery with it.
   */
  def scoreWithScript(query: ElasticQueryBuilder, script: String,
                      namesAndParams: Map[String, Any], native: Boolean = true): ElasticQueryBuilder = {
    val customScoreQuery = new FunctionScoreQueryBuilder(query)

    val ssfb = new ScriptScoreFunctionBuilder().lang(if (native) "native" else "mvel").script(script)
    namesAndParams.foreach {
      case (name, param) => ssfb.param(name, param)
    }
    customScoreQuery.add(ssfb)
  }

  def combineFilters(filters: List[ElasticFilterBuilder]): ElasticFilterBuilder = {
    new AndFilterBuilder(filters: _*)
  }
}

trait SolrSchema[M <: Record[M]] extends SlashemSchema[M] {
  self: M with SlashemSchema[M] =>

  def meta: SolrMeta[M]
  // 'Where' is the entry method for a SolrRogue query.

  def queryParams[Ord, Lim, MM <: MinimumMatchType, Select, H <: Highlighting, Q <: QualityFilter, FC <: FacetCount, FLim, ST <: ScoreType](qb: QueryBuilder[M, Ord, Lim, MM, Select, H, Q, FC, FLim, ST]): Seq[(String, String)] = queryParamsWithBounds(qb, qb.start, qb.limit)

  def queryParamsWithBounds[Ord, Lim, MM <: MinimumMatchType, Select, H <: Highlighting, Q <: QualityFilter, FC <: FacetCount, FLim, ST <: ScoreType](qb: QueryBuilder[M, Ord, Lim, MM, Select, H, Q, FC, FLim, ST], qstart: Option[Long], qrows: Option[Long]): Seq[(String, String)] = {
    val bounds = List("start" -> qstart.getOrElse {
      qb.DefaultStart
    }.toString,
      "rows" -> qrows.getOrElse {
        qb.DefaultLimit
      }.toString)
    bounds ++ queryParamsNoBounds(qb)
  }

  //This is the part which generates most of the solr request
  def queryParamsNoBounds[Ord, Lim, MM <: MinimumMatchType, Select, H <: Highlighting, Q <: QualityFilter, FC <: FacetCount, FLim, ST <: ScoreType](qb: QueryBuilder[M, Ord, Lim, MM, Select, H, Q, FC, FLim, ST]): Seq[(String, String)] = {

    //The actual query
    val p = List("q" -> qb.clauses.extend)

    //The query type. Most likely edismax or dismax
    val qt = qb.queryType match {
      case None => Nil
      case Some(method) => List("defType" -> method)
    }

    // Minimum match. If this is set to 100% then it is the same as setting
    // the default operation as AND
    val mm = qb.minimumMatch match {
      case None => Nil
      case Some(mmParam) => List("mm" -> mmParam)
    }

    //Facet field
    val ff = qb.facetSettings.facetFieldList match {
      case Nil => Nil
      case _ =>
        List("facet" -> "true", "facet.method" -> qb.facetSettings.facetMethod.toString) ++
          qb.facetSettings.facetFieldList.map(field => "facet.field" -> field.boost)
    }

    //Facet settings
    val fs = (qb.facetSettings.facetMinCount match {
      case None => Nil
      case Some(x) => List("facet.mincount" -> x.toString)
    }) ++ (qb.facetSettings.facetLimit match {
      case None => Nil
      case Some(x) => List("facet.limit" -> x.toString)
    })

    //Boost queries only impact scoring
    val bq = qb.boostQueries.map({ x => "bq" -> x.extend })

    val qf = qb.queryFields.filter({ x => x.weight != 0 }).map({ x => "qf" -> x.boost })

    val pf = qb.phraseBoostFields.filter(x => x.pf).map({ x => "pf" -> x.extend }) ++
      qb.phraseBoostFields.filter(x => x.pf2).map({ x => "pf2" -> x.extend }) ++
      qb.phraseBoostFields.filter(x => x.pf3).map({ x => "pf3" -> x.extend })

    val t = qb.tieBreaker match {
      case None => Nil
      case Some(x) => List("tieBreaker" -> x.toString)
    }

    val hlp = (qb.hls, qb.hlFragSize) match {
      case (Some(a), Some(b)) => List("hl" -> a, "hl.fragsize" -> b.toString)
      case (Some(a), None) => List("hl" -> a)
      case (None, _) => Nil
    }

    val bf = qb.boostFields.map({ x => "bf" -> x.boost })

    val f = qb.filters.map({ x => "fq" -> x.extend })

    val facetq = qb.facetSettings.facetQuery.flatMap(fq => List("facet.query" -> fq))
    val fl = (qb.pt, qb.fieldsToFetch) match {
      case (Some(a), Nil) => List("fl" -> "*,_dist_:geodist()")
      case (None, Nil) => Nil
      case (Some(a), x) => List("fl" -> (x.filterNot(_ == "geodist()") ++ List("_dist_:geodist()") mkString ","))
      case (None, x) => List("fl" -> x.mkString(","))
    }

    val s = (qb.pt, qb.sort) match {
      case (_, None) => Nil
      case (None, Some(sort)) => List("sort" -> (sort._1.boost + " " + sort._2))
      case (Some(a), Some(sort)) => List("sort" -> (sort._1.boost().replaceAll("_dist_", "geodist()") + " " + sort._2))
    }

    val ptq = qb.pt match {
      case None => Nil
      case Some(a) =>
        val res = List(
          "sfield" -> a.field,
          "d" -> a.distance.toString,
          "pt" -> "%s,%s".format(a.lat, a.lng))
        if (!a.bbox) res else res ++ List("fq" -> "{!bbox}")
    }

    t ++ mm ++ qt ++ bq ++ qf ++ p ++ s ++ f ++ facetq ++ pf ++ fl ++ bf ++ hlp ++ ff ++ fs ++ ptq
  }

  def query[Ord, Lim, MM <: MinimumMatchType, Y, H <: Highlighting, Q <: QualityFilter, FC <: FacetCount, FLim, ST <: ScoreType](timeout: Duration, qb: QueryBuilder[M, Ord, Lim, MM, Y, H, Q, FC, FLim, ST]): SearchResults[M, Y] = {
    Await.result(queryFuture(qb), timeout)
  }

  def queryFuture[Ord, Lim, MM <: MinimumMatchType, Y, H <: Highlighting, Q <: QualityFilter, FC <: FacetCount, FLim, ST <: ScoreType](qb: QueryBuilder[M, Ord, Lim, MM, Y, H, Q, FC, FLim, ST]): Future[SearchResults[M, Y]] = {
    solrQueryFuture(qb.creator, queryParams(qb), qb.fieldsToFetch, qb.fallOf, qb.min)
  }
  //The query builder calls into this to do actually execute the query.
  def solrQueryFuture[Y](creator: Option[Response.RawDoc => Y],
                         params: Seq[(String, String)],
                         fieldstofetch: List[String],
                         fallOf: Option[Double],
                         min: Option[Int]): Future[SearchResults[M, Y]] = {
    val queryName = meta.solrName + ".query"
    val queryText = meta.queryString(params).toString

    timeFuture(meta.rawQueryFuture(params)).map({
      case (queryTime, jsonString) =>
        meta.logger.log(queryName, queryText, queryTime)
        jsonString
    }).flatMap(jsonString => {
      meta.extractFromResponse(jsonString, creator,
        fieldstofetch,
        fallOf,
        min,
        queryText)
    })
      .onSuccess((v: SearchResults[M, Y]) => {
        meta.logger.success(meta.solrName)
        meta.logger.resultCount(meta.solrName, v.response.numFound)
      })
      .onFailure(e => meta.logger.failure(meta.solrName, queryText, e))
  }

}

/**
 * A field type for unanalyzed queries. Results in using Term[V] queries.
 */
trait SlashemUnanalyzedField[V, M <: Record[M]] extends SlashemField[V, M] {
  self: Field[V, M] =>

  override val unanalyzed = true
}

trait SlashemField[V, M <: Record[M]] extends OwnedField[M] {
  self: Field[V, M] =>
  import Helpers._

  // Override this value to produce unanalyzed queries!
  val unanalyzed = false

  def produceQuery(v: V, escapeQuery: Boolean = true): Query[V] = {
    unanalyzed match {
      case true => Term(List(v), escapeQuery)
      case false => Phrase(v, escapeQuery)
    }
  }

  def produceGroupedQuery(v: Iterable[V], escapeQuery: Boolean = true): Query[V] = {
    unanalyzed match {
      // we don't want to groupWithOr and instead take advantage of "terms" queries
      case true => Term(v, escapeQuery)
      case false => groupWithOr(v.map({ x: V => produceQuery(x, escapeQuery) }))
    }
  }

  def eqs(v: V) = Clause[V](self.queryName, Group(produceQuery(v)))
  def neqs(v: V) = Clause[V](self.queryName, produceQuery(v), plus = false)
  //With a boost
  def eqs(v: V, b: Float) = Clause[V](self.queryName, Boost(Group(produceQuery(v)), b))
  def neqs(v: V, b: Float) = Clause[V](self.queryName, Boost(produceQuery(v), b), plus = false)

  //This allows for bag of words style matching.
  def contains(v: V) = Clause[V](self.queryName, Group(BagOfWords(v)))
  def contains(v: V, b: Float) = Clause[V](self.queryName, Boost(Group(BagOfWords(v)), b))

  //Search with explicit escaping. By normal we escape, set e to false to disable
  //Note eqs and neqs results in phrase queries!
  def eqs(v: V, e: Boolean) = Clause[V](self.queryName, Group(produceQuery(v, e)))
  def neqs(v: V, e: Boolean) = Clause[V](self.queryName, produceQuery(v, e), plus = false)
  //With a boost
  def eqs(v: V, b: Float, e: Boolean) = Clause[V](self.queryName, Boost(Group(produceQuery(v, e)), b))
  def neqs(v: V, b: Float, e: Boolean) = Clause[V](self.queryName, Boost(produceQuery(v, e), b), plus = false)
  //This allows for bag of words style matching.
  def contains(v: V, e: Boolean) = Clause[V](self.queryName, Group(BagOfWords(v, e)))
  def contains(v: V, b: Float, e: Boolean) = Clause[V](self.queryName, Boost(Group(BagOfWords(v, e)), b))

  def in(v: Iterable[V]) = Clause[V](self.queryName, produceGroupedQuery(v))
  def nin(v: Iterable[V]) = Clause[V](self.queryName, produceGroupedQuery(v), plus = false)

  def in(v: Iterable[V], b: Float) = Clause[V](self.queryName, Boost(produceGroupedQuery(v), b))
  def nin(v: Iterable[V], b: Float) = Clause[V](self.queryName, Boost(produceGroupedQuery(v), b), plus = false)

  def inRange(v1: V, v2: V) = Clause[V](self.queryName, Group(Range(BagOfWords(v1), BagOfWords(v2))))
  def ninRange(v1: V, v2: V) = Clause[V](self.queryName, Group(Range(BagOfWords(v1), BagOfWords(v2))), plus = false)

  def lessThan(v: V) = Clause[V](self.queryName, Group(Range(Splat[V](), BagOfWords[V](v))))
  def greaterThan(v: V) = Clause[V](self.queryName, Group(Range(BagOfWords[V](v), Splat[V]())))

  def any = Clause[V](self.queryName, Splat[V]())

  def query(q: Query[V]) = Clause[V](self.queryName, q)

  def setFromAny(a: Any): Box[V]

  def valueBoxFromAny(a: Any): Box[V] = {
    try {
      Full(a.asInstanceOf[V])
    } catch {
      case _: Throwable => Empty
    }
  }
  //Support for highlighting matches
  var hl: List[String] = Nil
  def highlighted: List[String] = {
    hl
  }
  def setHighlighted(a: List[String]) = {
    hl = a
  }

  // Allow for a seperate name to be used for queries
  // useful for ES where a name might be stored as "name"
  // and then indexed as "name.edgengram" etc.
  def queryName = name

}

//Slashem field types
class SlashemStringField[T <: Record[T]](owner: T) extends StringField[T](owner, 0) with SlashemField[String, T]
/**
 * Field type that can be queried without analyzing.
 *
 * Ex: multi-value field or a whitespace tokenized field where
 * search terms are always for a specific token.
 *
 * @see SlashemStringField
 */
class SlashemUnanalyzedStringField[T <: Record[T]](owner: T)
  extends StringField[T](owner, 0) with SlashemUnanalyzedField[String, T]

//Allows for querying against the default filed in solr. This field doesn't have a name
class SlashemDefaultStringField[T <: Record[T]](owner: T) extends StringField[T](owner, 0) with SlashemField[String, T] {
  override def name = ""
}
class SlashemIntField[T <: Record[T]](owner: T) extends IntField[T](owner) with SlashemField[Int, T]
class SlashemDoubleField[T <: Record[T]](owner: T) extends DoubleField[T](owner) with SlashemField[Double, T]
class SlashemLongField[T <: Record[T]](owner: T) extends LongField[T](owner) with SlashemField[Long, T]
class SlashemObjectIdField[T <: Record[T]](owner: T) extends ObjectIdField[T](owner) with SlashemField[ObjectId, T] {
  override def valueBoxFromAny(a: Any): Box[ObjectId] = objectIdBoxFromAny(a)
}
class SlashemIntListField[T <: Record[T]](owner: T) extends IntListField[T](owner) with SlashemField[List[Int], T] {
  import Helpers._
  override def valueBoxFromAny(a: Any) = {
    try {
      a match {
        case "" => Empty
        case ar: Array[Int] => Full(ar.toList)
        case ar: Array[Integer] => Full(ar.toList.map(x => x.intValue))
        case ar: util.ArrayList[_] => Full(set(ar.toArray.toList.map(x => x.asInstanceOf[Integer].intValue)))
        case s: String => Full(s.split(" ").map(x => x.toInt).toList)
        case _ => Empty
      }
    } catch {
      case _: Throwable => Empty
    }
  }
  def contains(item: Int) = {
    Clause[Int](queryName, Phrase(item))
  }
  /**
   * See if this list has any elements in that list.
   * @param lst the list to check for any intersections.
   */
  def in(lst: List[Int]) = Clause[Int](queryName, groupWithOr(lst.map({ i: Int => Phrase(i) })))
  def nin(lst: List[Int]) = Clause[Int](queryName, groupWithOr(lst.map({ i: Int => Phrase(i) })), plus = false)
}

class SlashemStringListField[T <: Record[T]](owner: T) extends StringListField[T](owner) with SlashemField[List[String], T] {
  import Helpers._
  override def valueBoxFromAny(a: Any) = {
    try {
      a match {
        case "" => Full(List(""))
        case strArr: Array[String] => Full(strArr.toList)
        case intArr: Array[Int] => Full(intArr.toList.map(int => int.toString))
        case integerArr: Array[Integer] => Full(integerArr.toList.map(integer => integer.toString))
        case _ => Empty
      }
    } catch {
      case _: Throwable => Empty
    }
  }
  def contains(item: String) = {
    Clause[String](queryName, Phrase(item))
  }
  /**
   * See if this list has any elements in that list.
   * @param v the list to check for any intersections.
   */
  def in(v: List[String]) = Clause[String](queryName, groupWithOr(v.map({ s: String => Phrase(s) })))
  def nin(v: List[String]) = Clause[String](queryName, groupWithOr(v.map({ s: String => Phrase(s) })), plus = false)
}

class SlashemLongListField[T <: Record[T]](owner: T) extends LongListField[T](owner) with SlashemField[List[Long], T] {
  import Helpers._
  override def valueBoxFromAny(a: Any) = {
    try {
      a match {
        case long: Long => Full(List(long))
        case strArr: Array[Long] => Full(strArr.toList)
        case intArr: Array[Int] => Full(intArr.toList.map(int => int.toLong))
        case str: String => Full(str.split(" ").map(s => s.toLong).toList)
        case _ => Empty
      }
    } catch {
      case _: Throwable => Empty
    }
  }
  def contains(item: Long) = {
    Clause[Long](queryName, Phrase(item))
  }
  /**
   * See if this list has any elements in that list.
   * @param lst the list to check for any intersections.
   */
  def in(lst: List[Long]) = Clause[Long](queryName, groupWithOr(lst.map({ l: Long => Phrase(l) })))
  def nin(lst: List[Long]) = Clause[Long](queryName, groupWithOr(lst.map({ l: Long => Phrase(l) })), plus = false)
}

class SlashemObjectIdListField[T <: Record[T]](owner: T) extends ObjectIdListField[T](owner) with SlashemField[List[ObjectId], T] {
  import Helpers._
  override def valueBoxFromAny(a: Any) = objectIdBoxFromAny(a)
  def contains(item: ObjectId) = {
    Clause[ObjectId](queryName, Phrase(item))
  }
  /**
   * See if this list has any elements in that list.
   * @param lst the list to check for any intersections.
   */
  def in(lst: List[ObjectId]) = Clause[ObjectId](queryName, groupWithOr(lst.map({ oid: ObjectId => Phrase(oid) })))
  def nin(lst: List[ObjectId]) = Clause[ObjectId](queryName, groupWithOr(lst.map({ oid: ObjectId => Phrase(oid) })), plus = false)
}

class SlashemPointField[T <: Record[T]](owner: T) extends PointField[T](owner) with SlashemField[(Double, Double), T] {
  def geoDistance(geolat: Double, geolng: Double) = {
    GeoDist(this.name, geolat, geolng)
  }
  //Shortcut since we normally want the recip not the actual distance
  def recipGeoDistance(geolat: Double, geolng: Double, x: Int, y: Int, z: Int) = {
    Recip(GeoDist(this.name, geolat, geolng), x, y, z)
  }
  def sqeGeoDistance(geolat: Double, geolng: Double) = {
    GeoDist(this.name, geolat, geolng, "square")
  }
  //Shortcut since we normally want the recip not the actual distance
  def recipSqeGeoDistance(geolat: Double, geolng: Double, x: Int, y: Int, z: Int) = {
    Recip(GeoDist(this.name, geolat, geolng, "square"), x, y, z)
  }
}
class SlashemBooleanField[T <: Record[T]](owner: T) extends BooleanField[T](owner) with SlashemField[Boolean, T]
class SlashemDateTimeField[T <: Record[T]](owner: T) extends JodaDateTimeField[T](owner) with SlashemField[DateTime, T]
//More restrictive type so we can access the geohash
class SlashemGeoField[T <: SlashemSchema[T]](owner: T) extends SlashemUnanalyzedStringField[T](owner) {
  def inRadius(geoLat: Double, geoLong: Double, radiusInMeters: Int, maxCells: Int = owner.geohash.maxCells) = {
    val cellIds = owner.geohash.coverString(geoLat, geoLong, radiusInMeters, maxCells = maxCells)
    //If we have an empty cover we default to everything.
    cellIds match {
      case Nil => this.any
      case _ => this.in(cellIds)
    }
  }
  def inBox(topRight: (Double, Double), botLeft: (Double, Double), maxCells: Int = owner.geohash.maxCells) = {
    val cellIds = owner.geohash.rectCoverString(topRight, botLeft, maxCells = maxCells)
    //If we have an empty cover we default to everything.
    cellIds match {
      case Nil => this.any
      case _ => this.in(cellIds)
    }
  }
  def inBounds(bounds: GeoCover, maxCells: Int = owner.geohash.maxCells) = {
    val cellIds = bounds.boundsCoverString(maxCells = maxCells)
    cellIds match {
      case Nil => this.any
      case _ => this.in(cellIds)
    }
  }
}
// Legacy field name, in the future simply use Slashem*FieldName*
//Slashem field types
class SolrStringField[T <: Record[T]](owner: T) extends SlashemStringField[T](owner)
//Allows for querying against the default filed in solr. This field doesn't have a name
class SolrDefaultStringField[T <: Record[T]](owner: T) extends SlashemDefaultStringField[T](owner)
class SolrIntField[T <: Record[T]](owner: T) extends SlashemIntField[T](owner)
class SolrDoubleField[T <: Record[T]](owner: T) extends SlashemDoubleField[T](owner)
class SolrLongField[T <: Record[T]](owner: T) extends SlashemLongField[T](owner)
class SolrObjectIdField[T <: Record[T]](owner: T) extends SlashemObjectIdField[T](owner)
class SolrIntListField[T <: Record[T]](owner: T) extends SlashemIntListField[T](owner)
class SolrLongListField[T <: Record[T]](owner: T) extends SlashemLongListField[T](owner)
class SolrStringListField[T <: Record[T]](owner: T) extends SlashemStringListField[T](owner)
class SolrBooleanField[T <: Record[T]](owner: T) extends SlashemBooleanField[T](owner)
class SolrDateTimeField[T <: Record[T]](owner: T) extends SlashemDateTimeField[T](owner)
class SolrGeoField[T <: SlashemSchema[T]](owner: T) extends SlashemGeoField[T](owner)
// This insanity makes me want to 86 Record all together. DummyField allows us
// to easily define our own Field types. I use this for ObjectId so that I don't
// have to import all of MongoRecord. We could trivially reimplement the other
// Field types using it.
class ObjectIdField[T <: Record[T]](override val owner: T) extends Field[ObjectId, T] {

  type ValueType = ObjectId
  var e: Box[ValueType] = Empty

  def setFromString(s: String) = Full(set(new ObjectId(s)))

  // NOTE(benjy): We can't put this implementation directly in valueBoxFromAny, because SlashemObjectIdField
  // wouldn't be able to use this definition (it must redefine it so it can add the 'override' modifier, and it
  // can't call super.valueBoxFromAny because that would, according to the rules of linearization, invoke
  // SlashemField.valueBoxFromAny, which is not what we want).
  // TODO: This has bad code smell and indicates a brittle design.
  def objectIdBoxFromAny(a: Any): Box[ObjectId] = {
    try {
      a match {
        case "" => Empty
        case s: String => Full(new ObjectId(s))
        case i: ObjectId => Full(i)
        case _ => Empty
      }
    } catch {
      case _: Throwable => Empty
    }
  }

  def valueBoxFromAny(a: Any): Box[ObjectId] = objectIdBoxFromAny(a)

  override def setFromAny(a: Any) = {
    val vb = valueBoxFromAny(a)
    vb.map(set)
  }
  override def setFromJValue(jv: net.liftweb.json.JsonAST.JValue): Box[ValueType] = {
    implicit val formats = net.liftweb.json.DefaultFormats
    jv.extractOpt[String].flatMap(s => net.liftweb.util.Helpers.tryo(new ObjectId(s)))
  }
  override def liftSetFilterToBox(a: Box[ValueType]): Box[ValueType] = Empty
  override def toBoxMyType(a: ValueType): Box[ValueType] = Empty
  override def defaultValueBox: Box[ValueType] = Empty
  override def toValueType(a: Box[MyType]) = null.asInstanceOf[ValueType]
  override def asJValue: net.liftweb.json.JsonAST.JValue = net.liftweb.json.JsonAST.JNothing
  override def asJs: net.liftweb.http.js.JsExp = net.liftweb.http.js.JE.JsNull
  override def toForm: Box[NodeSeq] = Empty
  override def set(a: ValueType) = {
    e = Full(a)
    a.asInstanceOf[ValueType]
  }
  override def get = e.orNull
  override def is = e.orNull
  override def valueBox = e
}
class JodaDateTimeField[T <: Record[T]](override val owner: T) extends DummyField[DateTime, T](owner) {
  type ValueType = DateTime
  var e: Box[ValueType] = Empty

  override def setFromString(s: String): Box[ValueType] = {
    try {
      Full(set(new DateTime(s)))
    } catch {
      case _: Throwable => Empty
    }
  }
  override def setFromAny(a: Any): Box[ValueType] = {
    a match {
      case s: String => setFromString(s)
      case d: DateTime => Full(set(d))
      case _ => Empty
    }
  }
  override def set(a: ValueType) = {
    e = Full(a)
    a.asInstanceOf[ValueType]
  }
  override def get = e.orNull
  override def is = e.orNull
  override def valueBox = e
}

//This allows support for a list of integers as a field value.
class IntListField[T <: Record[T]](override val owner: T) extends Field[List[Int], T] {
  type ValueType = List[Int]
  var e: Box[ValueType] = Empty

  def setFromString(s: String) = {
    Full(set(s.split(" ").map(x => x.toInt).toList))
  }
  override def setFromAny(a: Any) = {
    try {
      a match {
        case "" => Empty
        case ar: Array[Int] => Full(set(ar.toList))
        case ar: Array[Integer] => Full(set(ar.toList.map(x => x.intValue)))
        case ar: util.ArrayList[_] => Full(set(ar.toArray.toList.map(x => x.asInstanceOf[Integer].intValue)))
        case s: String => Full(set(s.split(" ").map(x => x.toInt).toList))
        case _ => Empty
      }
    } catch {
      case _: Throwable => Empty
    }
  }
  override def setFromJValue(jv: net.liftweb.json.JsonAST.JValue): Box[ValueType] = {
    implicit val formats = net.liftweb.json.DefaultFormats
    jv.extractOpt[List[Int]]
  }
  override def liftSetFilterToBox(a: Box[ValueType]): Box[ValueType] = Empty
  override def toBoxMyType(a: ValueType): Box[ValueType] = Empty
  override def defaultValueBox: Box[ValueType] = Empty
  override def toValueType(a: Box[MyType]) = null.asInstanceOf[ValueType]
  override def asJValue: net.liftweb.json.JsonAST.JValue = net.liftweb.json.JsonAST.JNothing
  override def asJs: net.liftweb.http.js.JsExp = net.liftweb.http.js.JE.JsNull
  override def toForm: Box[NodeSeq] = Empty
  override def set(a: ValueType) = {
    e = Full(a)
    a.asInstanceOf[ValueType]
  }
  override def get = e.orNull
  override def is = e.orNull
  def value() = e getOrElse Nil
  override def valueBox = e
}

class LongListField[T <: Record[T]](override val owner: T) extends Field[List[Long], T] {
  type ValueType = List[Long]
  var e: Box[ValueType] = Empty

  def setFromString(s: String) = {
    Full(set(s.split(" ").map(x => x.toLong).toList))
  }
  override def setFromAny(a: Any) = {
    try {
      a match {
        case "" => Empty
        case ar: Array[Long] => Full(set(ar.toList))
        case ar: Array[Integer] => Full(set(ar.toList.map(x => x.longValue)))
        case ar: util.ArrayList[_] => Full(set(ar.toArray.asInstanceOf[Array[_]].toList.map {
          case i: Integer => i.toLong
          case l: Long => l
        }))
        case s: String => Full(set(s.split(" ").map(x => x.toLong).toList))
        case _ => Empty
      }
    } catch {
      case _: Throwable => Empty
    }
  }
  override def setFromJValue(jv: net.liftweb.json.JsonAST.JValue): Box[ValueType] = {
    implicit val formats = net.liftweb.json.DefaultFormats
    jv.extractOpt[List[Long]]
  }
  override def liftSetFilterToBox(a: Box[ValueType]): Box[ValueType] = Empty
  override def toBoxMyType(a: ValueType): Box[ValueType] = Empty
  override def defaultValueBox: Box[ValueType] = Empty
  override def toValueType(a: Box[MyType]) = null.asInstanceOf[ValueType]
  override def asJValue: net.liftweb.json.JsonAST.JValue = net.liftweb.json.JsonAST.JNothing
  override def asJs: net.liftweb.http.js.JsExp = net.liftweb.http.js.JE.JsNull
  override def toForm: Box[NodeSeq] = Empty
  override def set(a: ValueType) = {
    e = Full(a)
    a.asInstanceOf[ValueType]
  }
  override def get = e.orNull
  override def is = e.orNull
  def value() = e getOrElse Nil
  override def valueBox = e
}

class ObjectIdListField[T <: Record[T]](override val owner: T) extends Field[List[ObjectId], T] {
  type ValueType = List[ObjectId]
  var e: Box[ValueType] = Empty

  def setFromString(s: String) = {
    Full(set(s.split(" ").map(x => new ObjectId(x)).toList))
  }
  // NOTE(simon): See notes for ObjectIdField#objectIdBoxFromAny
  def objectIdBoxFromAny(a: Any): Box[List[ObjectId]] = {
    try {
      a match {
        case "" => Empty
        case ar: Array[ObjectId] => Full(ar.toList)
        case ar: Array[String] => Full(ar.toList.map(x => new ObjectId(x)))
        case ar: util.ArrayList[_] => Full(set(ar.toArray.toList.map(x => new ObjectId(x.asInstanceOf[String]))))
        case s: String => Full(s.split(" ").map(x => new ObjectId(x)).toList)
        case _ => Empty
      }
    } catch {
      case _: Throwable => Empty
    }
  }
  def valueBoxFromAny(a: Any): Box[List[ObjectId]] = objectIdBoxFromAny(a)
  override def setFromAny(a: Any) = {
    val vb = valueBoxFromAny(a)
    vb.map(set)
  }
  override def setFromJValue(jv: net.liftweb.json.JsonAST.JValue): Box[ValueType] = {
    implicit val formats = net.liftweb.json.DefaultFormats
    jv.extractOpt[List[String]].map(_.flatMap(s => net.liftweb.util.Helpers.tryo(new ObjectId(s))))
  }
  override def liftSetFilterToBox(a: Box[ValueType]): Box[ValueType] = Empty
  override def toBoxMyType(a: ValueType): Box[ValueType] = Empty
  override def defaultValueBox: Box[ValueType] = Empty
  override def toValueType(a: Box[MyType]) = null.asInstanceOf[ValueType]
  override def asJValue: net.liftweb.json.JsonAST.JValue = net.liftweb.json.JsonAST.JNothing
  override def asJs: net.liftweb.http.js.JsExp = net.liftweb.http.js.JE.JsNull
  override def toForm: Box[NodeSeq] = Empty
  override def set(a: ValueType) = {
    e = Full(a)
    a.asInstanceOf[ValueType]
  }
  override def get = e.orNull
  override def is = e.orNull
  def value() = e getOrElse Nil
  override def valueBox = e
}

class StringListField[T <: Record[T]](override val owner: T) extends Field[List[String], T] {
  type ValueType = List[String]
  var e: Box[ValueType] = Empty
  def setFromString(s: String) = {
    Full(set(s.split(" ").toList))
  }
  override def setFromAny(a: Any) = {
    try {
      a match {
        case "" => Empty
        case arr: Array[String] => Full(arr.toList)
        case str: String => setFromString(str)
        case ar: util.ArrayList[_] => Full(set(ar.toArray.toList.map(x => x.asInstanceOf[String])))
        case _ => Empty
      }
    } catch {
      case _: Throwable => Empty
    }
  }
  override def setFromJValue(jv: net.liftweb.json.JsonAST.JValue): Box[ValueType] = {
    implicit val formats = net.liftweb.json.DefaultFormats
    jv.extractOpt[List[String]]
  }
  override def liftSetFilterToBox(a: Box[ValueType]): Box[ValueType] = Empty
  override def toBoxMyType(a: ValueType): Box[ValueType] = Empty
  override def defaultValueBox: Box[ValueType] = Empty
  override def toValueType(a: Box[MyType]) = null.asInstanceOf[ValueType]
  override def asJValue: net.liftweb.json.JsonAST.JValue = net.liftweb.json.JsonAST.JNothing
  override def asJs: net.liftweb.http.js.JsExp = net.liftweb.http.js.JE.JsNull
  override def toForm: Box[NodeSeq] = Empty
  override def set(a: ValueType) = {
    e = Full(a)
    a.asInstanceOf[ValueType]
  }
  override def get = e.orNull
  override def is = e.orNull
  def value() = e getOrElse Nil
  override def valueBox = e
}

class PointField[T <: Record[T]](override val owner: T) extends Field[(Double, Double), T] {
  type ValueType = (Double, Double)
  var e: Box[ValueType] = Empty

  def setFromString(s: String) = {
    val doubles = s.split(",").map(x => x.toDouble).toList
    doubles.length match {
      case 2 => Full(set(Tuple2(doubles.head, doubles.apply(1))))
      case _ => Empty
    }
  }
  override def setFromAny(a: Any) = {
    try {
      a match {
        case "" => Empty
        /*
       * GeoJSON has (long, lat) instead of (lat, long)
       * Only ES uses GeoJSON spec and returns an ArrayList
       *
       */
        case ar: Array[Double] => Full(set(Tuple2(ar.apply(0), ar.apply(1))))
        case (lat: Double) :: (lng: Double) :: Nil => Full(set(Tuple2(lat, lng)))
        case arl: util.ArrayList[_] => Full(set(Tuple2(arl.get(1).asInstanceOf[Double], arl.get(0).asInstanceOf[Double])))
        case s: String => setFromString(s)
        case _ => Empty
      }
    } catch {
      case _: Throwable => Empty
    }
  }
  override def setFromJValue(jv: net.liftweb.json.JsonAST.JValue): Box[ValueType] = Empty
  override def liftSetFilterToBox(a: Box[ValueType]): Box[ValueType] = Empty
  override def toBoxMyType(a: ValueType): Box[ValueType] = Empty
  override def defaultValueBox: Box[ValueType] = Empty
  override def toValueType(a: Box[MyType]) = null.asInstanceOf[ValueType]
  override def asJValue: net.liftweb.json.JsonAST.JValue = net.liftweb.json.JsonAST.JNothing
  override def asJs: net.liftweb.http.js.JsExp = net.liftweb.http.js.JE.JsNull
  override def toForm: Box[NodeSeq] = Empty
  override def set(a: ValueType) = {
    e = Full(a)
    a.asInstanceOf[ValueType]
  }
  override def get = e.orNull
  override def is = e.orNull
  def value() = e.orNull
  override def valueBox = e
}
class DummyField[V, T <: Record[T]](override val owner: T) extends Field[V, T] {
  override def setFromString(s: String): Box[V] = Empty
  override def setFromAny(a: Any): Box[V] = Empty
  override def setFromJValue(jv: net.liftweb.json.JsonAST.JValue): Box[V] = Empty
  override def liftSetFilterToBox(a: Box[V]): Box[V] = Empty
  override def toBoxMyType(a: ValueType): Box[V] = Empty
  override def defaultValueBox: Box[V] = Empty
  override def toValueType(a: Box[MyType]) = null.asInstanceOf[ValueType]
  override def asJValue: net.liftweb.json.JsonAST.JValue = net.liftweb.json.JsonAST.JNothing
  override def asJs: net.liftweb.http.js.JsExp = net.liftweb.http.js.JE.JsNull
  override def toForm: Box[NodeSeq] = Empty
  override def set(a: ValueType) = null.asInstanceOf[ValueType]
  override def get = null.asInstanceOf[ValueType]
  override def is = null.asInstanceOf[ValueType]
}
