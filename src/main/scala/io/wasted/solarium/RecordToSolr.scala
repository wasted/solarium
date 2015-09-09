package io.wasted.solarium

import com.twitter.util.Future
import io.netty.handler.codec.http.{ FullHttpResponse, HttpHeaders, HttpMethod }
import io.netty.util.CharsetUtil
import net.liftweb.json._
import net.liftweb.record.Field

/**
 * Trait to export Lift Record objects to SOLR
 * @tparam T SolrSchema
 * @tparam PK PrimaryKey Type (String, Int)
 */
trait RecordToSolr[T <: SolrSchema[T], PK] { this: SolrSchema[T] =>
  implicit val solrFormats = DefaultFormats

  def primaryKeyField: Field[PK, T]

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
   * @return Future Unit if all goes well
   */
  def saveToSolr(): Future[Unit] = {
    val (host, client) = meta.getClient
    val json = Serialization.write(List(asJValueForSolr)).getBytes(CharsetUtil.UTF_8).toSeq
    val hdrs = Map(HttpHeaders.Names.HOST -> host)
    val uri = new java.net.URI("http://%s%s".format(host, updatePath))
    client.post(uri, "application/json", json, hdrs, HttpMethod.POST).map { resp =>
      val msg = resp.content().toString(CharsetUtil.UTF_8)
      resp.release()
      if (resp.getStatus.code() == 200) ()
      else {
        throw new Exception("Unable to save to backend! Status: %s Message: %s".format(resp.getStatus.code(), msg))
      }
    }
  }

  /**
   * Save a collection of records to SOLR
   * @return Future Unit if all goes well
   */
  def saveToSolr(list: Iterator[T with RecordToSolr[T, PK]]): Future[Unit] = {
    val (host, client) = meta.getClient
    val json = Serialization.write(list.map(_.asJValueForSolr)).getBytes(CharsetUtil.UTF_8).toSeq
    val hdrs = Map(HttpHeaders.Names.HOST -> host)
    val uri = new java.net.URI("http://%s%s".format(host, updatePath))
    client.post(uri, "application/json", json, hdrs, HttpMethod.POST).map { resp =>
      val msg = resp.content().toString(CharsetUtil.UTF_8)
      resp.release()
      if (resp.getStatus.code() == 200) ()
      else {
        throw new Exception("Unable to save to backend! Status: %s Message: %s".format(resp.getStatus.code(), msg))
      }
    }
  }

  /**
   * Save a collection of records to SOLR
   * @return Future Unit if all goes well
   */
  def saveToSolr(list: Iterable[T with RecordToSolr[T, PK]]): Future[Unit] = saveToSolr(list.toIterator)

  /**
   * Delete a collection of records to SOLR
   * @return Future Unit if all goes well
   */
  def deleteByIdsFromSolr(list: Iterator[PK]): Future[Unit] = {
    val (host, client) = meta.getClient
    val deleteFields = list.map(l => JField("delete", JObject(JField(primaryKeyField.name, Extraction.decompose(l)) :: Nil)))
    val json = Serialization.write(JObject(deleteFields.toList)).getBytes(CharsetUtil.UTF_8).toSeq
    val hdrs = Map(HttpHeaders.Names.HOST -> host)
    val uri = new java.net.URI("http://%s%s".format(host, updatePath))
    client.post(uri, "application/json", json, hdrs, HttpMethod.POST).map { resp =>
      val msg = resp.content().toString(CharsetUtil.UTF_8)
      resp.release()
      if (resp.getStatus.code() == 200) ()
      else {
        throw new Exception("Unable to save to backend! Status: %s Message: %s".format(resp.getStatus.code(), msg))
      }
    }
  }

  /**
   * Delete a collection of records to SOLR
   * @return Future Unit if all goes well
   */
  def deleteByIdsFromSolr(list: Iterable[PK]): Future[Unit] = deleteByIdsFromSolr(list.toIterator)

  /**
   * Delete a collection of records to SOLR
   * @return Future Unit if all goes well
   */
  def deleteFromSolr(list: Iterator[T with RecordToSolr[T, PK]]): Future[Unit] = {
    deleteByIdsFromSolr(list.map(_.primaryKeyField.get.asInstanceOf[PK]))
  }

  /**
   * Delete a collection of records to SOLR
   * @return Future Unit if all goes well
   */
  def deleteFromSolr(list: Iterable[T with RecordToSolr[T, PK]]): Future[Unit] = {
    deleteByIdsFromSolr(list.map(_.primaryKeyField.get.asInstanceOf[PK]).toIterator)
  }

  /**
   * Commit changes on this core/collection to SOLR
   * @return Future Unit if all goes well
   */
  final def commitToSolr(): Future[Unit] = {
    val (host, client) = meta.getClient
    val hdrs = Map(HttpHeaders.Names.HOST -> host)
    client.get(new java.net.URI("http://%s%s?commit=true".format(host, updatePath)), hdrs).map { resp =>
      val msg = resp.content().toString(CharsetUtil.UTF_8)
      resp.release()
      if (resp.getStatus.code() == 200) ()
      else {
        throw new Exception("Unable to commit to backend! Status: %s Message: %s".format(resp.getStatus.code(), msg))
      }
    }
  }

  /**
   * Easy Liftweb-style .save method
   *
   * @param commit Wether to commit or not
   * @return Future Unit if all went well
   */
  def save(commit: Boolean = false): Future[Unit] = {
    val saveF = saveToSolr()
    if (!commit) saveF else saveF.flatMap { saved =>
      commitToSolr()
    }
  }

}
