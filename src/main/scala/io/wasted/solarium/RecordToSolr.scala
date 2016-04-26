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
  def asJValueForSolr: JObject = {
    // .toMap here prevents double keys
    JObject(fields().map(f => f.name -> f.asJValue).toMap.map {
      case (name, value) => JField(name, value)
    }.toList)
  }

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
    if (list.isEmpty) Future.Done
    else {
      val (host, client) = meta.getClient
      val json = Serialization.write(list.map(_.asJValueForSolr).toList).getBytes(CharsetUtil.UTF_8).toSeq
      val hdrs = Map(HttpHeaders.Names.HOST -> host)
      val uri = new java.net.URI("http://%s%s".format(host, updatePath))
      client.post(uri, "application/json", json, hdrs, HttpMethod.POST).map { resp =>
        val msg = resp.content().toString(CharsetUtil.UTF_8)
        resp.release()
        if (resp.getStatus.code() == 200) ()
        else {
          throw new Exception("Unable to save to backend! Status: %s Message: %s\nSent: %s".format(
            resp.getStatus.code(), msg, Serialization.writePretty(list.map(_.asJValueForSolr).toList)))
        }
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
    if (list.isEmpty) Future.Done
    else {
      val (host, client) = meta.getClient
      val delete = """{"delete":{"query":"%s:(%s)"}}""".format(primaryKeyField.name, list.mkString(" OR "))
      val hdrs = Map(HttpHeaders.Names.HOST -> host)
      val uri = new java.net.URI("http://%s%s".format(host, updatePath))
      client.post(uri, "application/json", delete.getBytes(CharsetUtil.UTF_8).toSeq, hdrs, HttpMethod.POST).map { resp =>
        val msg = resp.content().toString(CharsetUtil.UTF_8)
        resp.release()
        if (resp.getStatus.code() == 200) ()
        else {
          throw new Exception("Unable to delete from backend! Status: %s Message: %s\nSent: %s".format(
            resp.getStatus.code(), msg, delete))
        }
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
