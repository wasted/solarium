package io.wasted.solarium

import com.twitter.util.Awaitable
import io.netty.handler.codec.http.{ FullHttpResponse, HttpHeaders, HttpMethod }
import io.netty.util.CharsetUtil
import net.liftweb.json._

/**
 * Trait to export Lift Record objects to SOLR
 * @tparam T SolrSchema
 */
trait RecordToSolr[T <: SolrSchema[T]] { this: SolrSchema[T] =>
  implicit val solrFormats = DefaultFormats

  /* Need a more crazy query path? override */
  lazy val updatePath: String = meta.core match {
    case None => "/solr/update/"
    case Some(x) => "/solr/%s/update".format(x)
  }

  /**
   * Generates the JSON which is being saved to SOLR.
   * Defaults to asJValue
   * @return JValue being saved into SOLR
   */
  def asJValueForSolr: JValue = this.asJValue

  /**
   * Save this record to SOLR
   * @return Future HTTP Response
   */
  def saveToSolr(): Awaitable[FullHttpResponse] = {
    val (host, client) = meta.getClient
    val json = Serialization.write(List(asJValueForSolr)).getBytes(CharsetUtil.UTF_8).toSeq
    val hdrs = Map(HttpHeaders.Names.HOST -> host)
    client.post(new java.net.URI("http://%s%s".format(host, updatePath)), "application/json", json, hdrs, HttpMethod.POST)
  }

  /**
   * Save a collection of records to SOLR
   * @return Future HTTP Response
   */
  def saveToSolr(list: Iterator[T with RecordToSolr[T]]): Awaitable[FullHttpResponse] = {
    val (host, client) = meta.getClient
    val json = Serialization.write(list.map(_.asJValueForSolr)).getBytes(CharsetUtil.UTF_8).toSeq
    val hdrs = Map(HttpHeaders.Names.HOST -> host)
    client.post(new java.net.URI("http://%s%s".format(host, updatePath)), "application/json", json, hdrs, HttpMethod.POST)
  }

  /**
   * Save a collection of records to SOLR
   * @return Future HTTP Response
   */
  def saveToSolr(list: Iterable[T with RecordToSolr[T]]): Awaitable[FullHttpResponse] = {
    val (host, client) = meta.getClient
    val json = Serialization.write(list.map(_.asJValueForSolr)).getBytes(CharsetUtil.UTF_8).toSeq
    val hdrs = Map(HttpHeaders.Names.HOST -> host)
    client.post(new java.net.URI("http://%s%s".format(host, updatePath)), "application/json", json, hdrs, HttpMethod.POST)
  }

  /**
   * Commit changes on this core/collection to SOLR
   * @return Future HTTP Response
   */
  final def commitToSolr(): Awaitable[FullHttpResponse] = {
    val (host, client) = meta.getClient
    val hdrs = Map(HttpHeaders.Names.HOST -> host)
    client.get(new java.net.URI("http://%s%s?commit=true".format(host, updatePath)), hdrs)
  }

}
