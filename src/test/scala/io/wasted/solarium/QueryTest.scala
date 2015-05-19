// Copyright 2011-2012 Foursquare Labs Inc. All Rights Reserved.
// Copyright (c) 2014, 2015, wasted.io Ltd.

package io.wasted.solarium

import com.twitter.util.{ Await, Duration }
import java.util.concurrent.TimeUnit
import org.bson.types.ObjectId
import org.junit.Test
import org.junit._

import org.scalatest.junit.JUnitSuite

class QueryTest extends JUnitSuite {

  @Test
  def testStartEndExecuteQuery {
    val oldLogger = SUserTest.logger
    try {
      var startCount = 0
      var endCount = 0
      SUserTest.logger = new SolrQueryLogger {
        override def onStartExecuteQuery(name: String, msg: String): Function0[Unit] = {
          startCount += 1
          () => {
            endCount += 1
          }
        }
        override def log(name: String, msg: String, time: Long): Unit = Unit
        override def debug(msg: String): Unit = Unit
        override def resultCount(name: String, count: Int): Unit = Unit
      }
      // this query should fail since there's no solr server
      // 10 second wait is upper limit to prevent race condition
      Await.result(SUserTest.where(_.fullname eqs "jon").fetchFuture(), Duration(10, TimeUnit.SECONDS))

      Assert.assertEquals("start should have been called just once", 1, startCount)
      Assert.assertEquals("end should have been called just once", 1, endCount)
    } catch {
      case a: java.net.ConnectException => // expected
    } finally {
      SUserTest.logger = oldLogger
    }
  }

  @Test
  def testProduceCorrectSimpleQueryString {
    val q = SUserTest where (_.fullname eqs "jon")
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("q" -> "fullname:(\"jon\")",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }
  @Test
  def testProduceCorrectSimpleQueryStringWithHighlighting {
    val q = SUserTest where (_.fullname eqs "jon") highlighting ()
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("q" -> "fullname:(\"jon\")",
      "start" -> "0",
      "hl" -> "on",
      "rows" -> "10").sortWith(_._1 > _._1))
  }
  @Test
  def testProduceCorrectSimpleQueryStringWithFaceting {
    val q = SUserTest where (_.fullname eqs "jon") facetField (_.fullname)
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("q" -> "fullname:(\"jon\")",
      "start" -> "0",
      "facet" -> "true",
      "facet.field" -> "fullname",
      "facet.method" -> "enum",
      "rows" -> "10").sortWith(_._1 > _._1))
  }

  @Test
  def testProduceCorrectSimpleEscapedQueryStringWithHighlighting {
    val q = SUserTest where (_.fullname contains "jon OR") highlighting ()
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("q" -> "fullname:(jon \"OR\")",
      "start" -> "0",
      "hl" -> "on",
      "rows" -> "10").sortWith(_._1 > _._1))
  }

  @Test
  def testProduceCorrectSimpleUnEscapedQueryString {
    val q = SUserTest where (_.fullname contains ("jon*", false))
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("q" -> "fullname:(jon*)",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }

  @Test
  def testProduceCorrectListfieldQueryStrings {
    val q = SVenueTest where (_.commentList in List("hi", "there"))
    val q2 = SVenueTest where (_.commentList nin List("hi", "there"))
    val qp = q.meta.queryParams(q).toList
    val qp2 = q2.meta.queryParams(q2).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1),
      List("q" -> "commentList:(\"hi\" OR \"there\")",
        "start" -> "0",
        "rows" -> "10").sortWith(_._1 > _._1))
    Assert.assertEquals(qp2.sortWith(_._1 > _._1),
      List("q" -> "(*:* -commentList:(\"hi\" OR \"there\"))",
        "start" -> "0",
        "rows" -> "10").sortWith(_._1 > _._1))
  }

  @Test
  def testProduceCorrectListfieldQueryStringWhenEmpty {
    val q = SVenueTest where (_.commentList in List())
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1),
      List("q" -> "commentList:(\"\")",
        "start" -> "0",
        "rows" -> "10").sortWith(_._1 > _._1))
  }
  @Test
  def testProduceCorrectListfieldQueryStringNinWhenEmpty {
    val q = SVenueTest where (_.commentList nin List())
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1),
      List("q" -> "(*:* -commentList:(\"\"))",
        "start" -> "0",
        "rows" -> "10").sortWith(_._1 > _._1))
  }

  @Test
  def testProduceCorrectSimpleQueryStringContains {
    val q = SUserTest where (_.fullname contains "jon")
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("q" -> "fullname:(jon)",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }

  @Test
  def testProduceCorrectSimpleEscaping {
    val q = SUserTest where (_.fullname contains "jon-smith")
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("q" -> "fullname:(jon\\-smith)",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }
  @Test
  def testProduceCorrectSimpleQueryStringWithBoostList {
    val q = SUserTest where (_.fullname eqs "jon") useQueryType ("edismax") boostQuery (_.friend_ids in List(110714, 1048882, 2775804, 364701, 33).map(_.toString))
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("q" -> """fullname:("jon")""",
      "start" -> "0",
      "defType" -> "edismax",
      "bq" -> """friend_ids:("110714" OR "1048882" OR "2775804" OR "364701" OR "33")""",
      "rows" -> "10").sortWith(_._1 > _._1))
  }

  @Test
  def testProduceCorrectSimpleQueryStringWithBoostNeq {
    val q = SUserTest where (_.fullname eqs "jon") useQueryType ("edismax") boostQuery (_.fullname neqs ("fnur", 123))
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("q" -> """fullname:("jon")""",
      "start" -> "0",
      "defType" -> "edismax",
      "bq" -> """(*:* -fullname:("fnur"))^123.0""",
      "rows" -> "10").sortWith(_._1 > _._1))
  }

  @Test
  def testProduceCorrectEdisMaxQueryString {
    val q = SUserTest where (_.fullname eqs "holden") useQueryType ("edismax")
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("defType" -> "edismax",
      "q" -> "fullname:(\"holden\")",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }
  @Test
  def testProduceCorrectEdisMaxQueryStringWithMinimumMatch {
    val q = SUserTest where (_.fullname eqs "jason") useQueryType ("edismax") minimumMatchPercent (75)
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("defType" -> "edismax",
      "mm" -> "75%",
      "q" -> "fullname:(\"jason\")",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }

  @Test
  def testProduceCorrectDefaultQuery {
    val q = SVenueTest where (_.default eqs "bedlam coffee") useQueryType ("edismax") minimumMatchPercent (75)
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("defType" -> "edismax",
      "mm" -> "75%",
      "q" -> "(\"bedlam coffee\")",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }
  @Test
  def testProduceCorrectFilterQuery {
    val q = SVenueTest where (_.default eqs "club") filter (_.tags neqs "douchebag") useQueryType ("edismax") minimumMatchPercent (75)
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("defType" -> "edismax",
      "mm" -> "75%",
      "q" -> "(\"club\")",
      "fq" -> "(*:* -tags:(\"douchebag\"))",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }
  @Test
  def testProduceCorrectSimpleLimit {
    val q = SUserTest where (_.fullname eqs "jon") limit 250
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("q" -> "fullname:(\"jon\")",
      "start" -> "0",
      "rows" -> "250").sortWith(_._1 > _._1))
  }
  @Test
  def testProduceCorrectWithQueryField {
    val q = SVenueTest where (_.default eqs "club") useQueryType ("edismax") queryField (_.name)
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("defType" -> "edismax",
      "q" -> "(\"club\")",
      "qf" -> "name",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }
  @Test
  def testProduceCorrectWithQueryFieldAndZeroBoost {
    val q = SVenueTest where (_.default eqs "club") useQueryType ("edismax") queryField (_.name, 0)
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("defType" -> "edismax",
      "q" -> "(\"club\")",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }
  @Test
  def testProduceCorrectWithQueryFieldAndOneBoost {
    val q = SVenueTest where (_.default eqs "club") useQueryType ("edismax") queryField (_.name, 1)
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("defType" -> "edismax",
      "q" -> "(\"club\")",
      "qf" -> "name",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }
  @Test
  def testProduceCorrectWithQueryFieldAndBoost {
    val q = SVenueTest where (_.default eqs "club") useQueryType ("edismax") queryField (_.name, 2)
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("defType" -> "edismax",
      "q" -> "(\"club\")",
      "qf" -> "name^2.0",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }
  @Test
  def testProduceCorrectWithQueryFieldAndNonIntBoost {
    val q = SVenueTest where (_.default eqs "club") useQueryType ("edismax") queryField (_.name, 2.5)
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("defType" -> "edismax",
      "q" -> "(\"club\")",
      "qf" -> "name^2.5",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }
  @Test
  def testProduceCorrectWithPhraseBoost {
    val q = SVenueTest where (_.default eqs "club") useQueryType ("edismax") phraseBoost (_.name, 2.5)
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("defType" -> "edismax",
      "q" -> "(\"club\")",
      "pf" -> "name^2.5",
      "pf2" -> "name^2.5",
      "pf3" -> "name^2.5",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }
  @Test
  def testProduceCorrectWithRange {
    val q = SVenueTest where (_.default inRange ("a", "z")) useQueryType ("edismax")
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("defType" -> "edismax",
      "q" -> "([a TO z])",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }

  @Test
  def testProduceCorrectWithLessThan {
    val q = SVenueTest where (_.default lessThan ("z")) useQueryType ("edismax")
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("defType" -> "edismax",
      "q" -> "([* TO z])",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }

  @Test
  def testProduceCorrectWithGreaterThan {
    val q = SVenueTest where (_.default greaterThan ("z")) useQueryType ("edismax")
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("defType" -> "edismax",
      "q" -> "([z TO *])",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }

  @Test
  def testProduceCorrectObjID {
    val q = SEventTest where (_.venueid eqs new ObjectId("4dc5bc4845dd2645527930a9"))
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("q" -> "venueid:(\"4dc5bc4845dd2645527930a9\")",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }

  @Test
  def testProduceCorrectWithDateRange {
    import org.joda.time.{ DateTime, DateTimeZone }
    val d1 = new DateTime(2011, 5, 1, 0, 0, 0, 0, DateTimeZone.UTC)
    val d2 = new DateTime(2011, 5, 2, 0, 0, 0, 0, DateTimeZone.UTC)
    val q = SEventTest where (_.start_time inRange (d1, d2))
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("q" -> "start_time:([2011\\-05\\-01T00\\:00\\:00.000Z TO 2011\\-05\\-02T00\\:00\\:00.000Z])",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }

  @Test
  def testProduceCorrectWithDateLessThan {
    import org.joda.time.{ DateTime, DateTimeZone }
    val d1 = new DateTime(2011, 5, 1, 0, 0, 0, 0, DateTimeZone.UTC)
    val q = SEventTest where (_.start_time lessThan (d1))
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("q" -> "start_time:([* TO 2011\\-05\\-01T00\\:00\\:00.000Z])",
      "start" -> "0",
      "rows" -> "10").sortWith(_._1 > _._1))
  }

  @Test
  def testFieldQuery {
    val q = SVenueTest where (_.default eqs "bedlam coffee") useQueryType ("edismax") fetchField (_.name) fetchField (_.address)
    val qp = q.meta.queryParams(q).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1), List("defType" -> "edismax",
      "q" -> "(\"bedlam coffee\")",
      "start" -> "0",
      "fl" -> "address,name", //Britle
      "rows" -> "10").sortWith(_._1 > _._1))
  }
  @Test
  def testAutoComplexQuery1 {
    val lols = "holden's hobohut"
    val q = SVenueTest where (_.default contains lols) useQueryType ("edismax") phraseBoost (_.text, 1) phraseBoost (_.name, 200) phraseBoost (_.aliases, 25) queryField (_.text) queryField (_.ngram_name, 0.2)
    val qp = q.meta.queryParams(q).toList
    val expected = List("defType" -> "edismax",
      "q" -> "(holden's hobohut)",
      "start" -> "0",
      "pf" -> "text",
      "pf2" -> "text",
      "pf3" -> "text",
      "pf" -> "name^200.0",
      "pf2" -> "name^200.0",
      "pf3" -> "name^200.0",
      "pf" -> "aliases^25.0",
      "pf2" -> "aliases^25.0",
      "pf3" -> "aliases^25.0",
      "qf" -> "text",
      "qf" -> "ngram_name^0.2",
      "rows" -> "10")
    Assert.assertEquals(qp.sortWith(((x, y) => (x._1 + x._2) > (y._1 + y._2))), expected.sortWith(((x, y) => (x._1 + x._2) > (y._1 + y._2))))
  }
  @Test
  def testAutoComplexQuery2 {
    val lols = "holden's hobohut"
    val q = SVenueTest where (_.default contains lols) useQueryType ("edismax") phraseBoost (_.text, 1) phraseBoost (_.name, 200) phraseBoost (_.aliases, 25) queryField (_.text) queryField (_.ngram_name, 0.2) queryField (_.tags, 0.01)
    val qp = q.meta.queryParams(q).toList
    val expected = List("defType" -> "edismax",
      "q" -> "(holden's hobohut)",
      "start" -> "0",
      "pf" -> "text",
      "pf2" -> "text",
      "pf3" -> "text",
      "pf" -> "name^200.0",
      "pf2" -> "name^200.0",
      "pf3" -> "name^200.0",
      "pf" -> "aliases^25.0",
      "pf2" -> "aliases^25.0",
      "pf3" -> "aliases^25.0",
      "qf" -> "text",
      "qf" -> "ngram_name^0.2",
      "qf" -> "tags^0.01",
      "rows" -> "10")
    Assert.assertEquals(qp.sortWith(((x, y) => (x._1 + x._2) > (y._1 + y._2))),
      expected.sortWith(((x, y) => (x._1 + x._2) > (y._1 + y._2))))
  }
  @Test
  def testAutoComplexQuery3 {
    val lols = "holden's hobohut"
    val geoLat = 37.7519528215759
    val geoLong = -122.42086887359619
    val q = SVenueTest where (_.default contains lols) useQueryType ("edismax") phraseBoost (_.text, 1) phraseBoost (_.name, 200) phraseBoost (_.aliases, 25) queryField (_.text) queryField (_.ngram_name, 0.2) queryField (_.tags, 0.01) tieBreaker (0.2)
    val qp = q.meta.queryParams(q).toList
    val expected = List("defType" -> "edismax",
      "q" -> "(holden's hobohut)",
      "start" -> "0",
      "pf" -> "text",
      "pf2" -> "text",
      "pf3" -> "text",
      "pf" -> "name^200.0",
      "pf2" -> "name^200.0",
      "pf3" -> "name^200.0",
      "pf" -> "aliases^25.0",
      "pf2" -> "aliases^25.0",
      "pf3" -> "aliases^25.0",
      "qf" -> "text",
      "qf" -> "ngram_name^0.2",
      "qf" -> "tags^0.01",
      "tieBreaker" -> "0.2",
      "rows" -> "10")
    Assert.assertEquals(Nil, ((qp.toSet &~ expected.toSet)).toList)
    Assert.assertEquals(Nil, (expected.toSet &~ qp.toSet).toList)
  }
  @Test
  def testAutoComplexQuery4 {
    val lols = "holden's hobohut"
    val geoLat = 37.7519528215759
    val geoLong = -122.42086887359619
    val q = SVenueTest where (_.default contains lols) useQueryType ("edismax") phraseBoost (_.text, 1) phraseBoost (_.name, 200) phraseBoost (_.aliases, 25) queryField (_.text) queryField (_.ngram_name, 0.2) queryField (_.tags, 0.01) tieBreaker (0.2) boostField (_.decayedPopularity1) boostField ("recip(sqedist(%s,%s,lat,lng), 1, 5000, 1)".format(geoLat, geoLong))
    val qp = q.meta.queryParams(q).toList
    val expected = List("defType" -> "edismax",
      "q" -> "(holden's hobohut)",
      "start" -> "0",
      "pf" -> "text",
      "pf2" -> "text",
      "pf3" -> "text",
      "pf" -> "name^200.0",
      "pf2" -> "name^200.0",
      "pf3" -> "name^200.0",
      "pf" -> "aliases^25.0",
      "pf2" -> "aliases^25.0",
      "pf3" -> "aliases^25.0",
      "qf" -> "text",
      "qf" -> "ngram_name^0.2",
      "qf" -> "tags^0.01",
      "tieBreaker" -> "0.2",
      "bf" -> "recip(sqedist(%s,%s,lat,lng), 1, 5000, 1)".format(geoLat, geoLong),
      "bf" -> "decayedPopularity1",
      "rows" -> "10")
    Assert.assertEquals(Nil, ((qp.toSet &~ expected.toSet)).toList)
    Assert.assertEquals(Nil, (expected.toSet &~ qp.toSet).toList)
  }

  @Test
  def testAutoComplexQuery5 {
    val lols = "holden's hobohut"
    val geoLat = 37.7519528215759
    val geoLong = -122.42086887359619
    val q = SVenueTest where (_.default contains lols) useQueryType ("edismax") phraseBoost (_.text, 1) phraseBoost (_.name, 200) phraseBoost (_.aliases, 25) queryField (_.text) queryField (_.ngram_name, 0.2) queryField (_.tags, 0.01) tieBreaker (0.2) boostField (_.decayedPopularity1) boostField ("recip(sqedist(%s,%s,lat,lng), 1, 5000, 1)".format(geoLat, geoLong)) boostQuery ((_.name contains lols))
    val qp = q.meta.queryParams(q).toList
    val expected = List("defType" -> "edismax",
      "q" -> "(holden's hobohut)",
      "start" -> "0",
      "pf" -> "text",
      "pf2" -> "text",
      "pf3" -> "text",
      "pf" -> "name^200.0",
      "pf2" -> "name^200.0",
      "pf3" -> "name^200.0",
      "pf" -> "aliases^25.0",
      "pf2" -> "aliases^25.0",
      "pf3" -> "aliases^25.0",
      "qf" -> "text",
      "qf" -> "ngram_name^0.2",
      "qf" -> "tags^0.01",
      "tieBreaker" -> "0.2",
      "bq" -> "name:(holden's hobohut)",
      "bf" -> "recip(sqedist(%s,%s,lat,lng), 1, 5000, 1)".format(geoLat, geoLong),
      "bf" -> "decayedPopularity1",
      "rows" -> "10")
    Assert.assertEquals(Nil, ((qp.toSet &~ expected.toSet)).toList)
    Assert.assertEquals(Nil, (expected.toSet &~ qp.toSet).toList)
  }

  @Test
  def testAutoComplexQuery6 {
    val lols = "holden's hobohut"
    val geoLat = 37.7519528215759
    val geoLong = -122.42086887359619
    val q = SVenueTest where (_.default contains lols) useQueryType ("edismax") phraseBoost (_.text, 1) phraseBoost (_.name, 200) phraseBoost (_.aliases, 25) queryField (_.text) queryField (_.ngram_name, 0.2) queryField (_.tags, 0.01) tieBreaker (0.2) boostField (_.decayedPopularity1) boostField ("recip(sqedist(%s,%s,lat,lng), 1, 5000, 1)".format(geoLat, geoLong)) boostQuery (_.name contains (lols, 10))
    val qp = q.meta.queryParams(q).toList
    val expected = List("defType" -> "edismax",
      "q" -> "(holden's hobohut)",
      "start" -> "0",
      "pf" -> "text",
      "pf2" -> "text",
      "pf3" -> "text",
      "pf" -> "name^200.0",
      "pf2" -> "name^200.0",
      "pf3" -> "name^200.0",
      "pf" -> "aliases^25.0",
      "pf2" -> "aliases^25.0",
      "pf3" -> "aliases^25.0",
      "qf" -> "text",
      "qf" -> "ngram_name^0.2",
      "qf" -> "tags^0.01",
      "tieBreaker" -> "0.2",
      "bq" -> "name:(holden's hobohut)^10.0",
      "bf" -> "recip(sqedist(%s,%s,lat,lng), 1, 5000, 1)".format(geoLat, geoLong),
      "bf" -> "decayedPopularity1",
      "rows" -> "10")
    Assert.assertEquals(Nil, ((qp.toSet &~ expected.toSet)).toList)
    Assert.assertEquals(Nil, (expected.toSet &~ qp.toSet).toList)
  }

  @Test
  def testAutoComplexQuery7 {
    val lols = "holden's hobohut"
    val geoLat = 37.7519528215759
    val geoLong = -122.42086887359619
    val q = SVenueTest where (_.default contains lols) useQueryType ("edismax") phraseBoost (_.text, 1) phraseBoost (_.name, 200) phraseBoost (_.aliases, 25) queryField (_.text) queryField (_.ngram_name, 0.2) queryField (_.tags, 0.01) tieBreaker (0.2) boostField (_.decayedPopularity1) boostField ("recip(sqedist(%s,%s,lat,lng), 1, 5000, 1)".format(geoLat, geoLong)) boostQuery (_.name contains (lols, 10)) fetchFields (_.id, _.name, _.userid, _.mayorid, _.category_id_0, _.popularity, _.decayedPopularity1, _.lat, _.lng, _.checkin_info, _.score, _.hasSpecial, _.address, _.crossstreet, _.city, _.state, _.zip, _.country, _.checkinCount)
    val qp = q.meta.queryParams(q).toList
    val expected = List("defType" -> "edismax",
      "q" -> "(holden's hobohut)",
      "start" -> "0",
      "pf" -> "text",
      "pf2" -> "text",
      "pf3" -> "text",
      "pf" -> "name^200.0",
      "pf2" -> "name^200.0",
      "pf3" -> "name^200.0",
      "pf" -> "aliases^25.0",
      "pf2" -> "aliases^25.0",
      "pf3" -> "aliases^25.0",
      "qf" -> "text",
      "qf" -> "ngram_name^0.2",
      "qf" -> "tags^0.01",
      "tieBreaker" -> "0.2",
      "fl" -> "id,name,userid,mayorid,category_id_0,popularity,decayedPopularity1,lat,lng,checkin_info,score,hasSpecial,address,crossstreet,city,state,zip,country,checkinCount",
      "bq" -> "name:(holden's hobohut)^10.0",
      "bf" -> "recip(sqedist(%s,%s,lat,lng), 1, 5000, 1)".format(geoLat, geoLong),
      "bf" -> "decayedPopularity1",
      "rows" -> "10")
    Assert.assertEquals(Nil, ((qp.toSet &~ expected.toSet)).toList)
    Assert.assertEquals(Nil, (expected.toSet &~ qp.toSet).toList)
  }
  @Test
  def testAutoComplexQuery8 {
    val lols = "holden's hobohut"
    val geoLat = 37.7519528215759
    val geoLong = -122.42086887359619
    val q = SVenueTest where (_.default contains lols) useQueryType ("edismax") phraseBoost (_.text, 1) phraseBoost (_.name, 200) phraseBoost (_.aliases, 25) queryField (_.text) queryField (_.ngram_name, 0.2) queryField (_.tags, 0.01) tieBreaker (0.2) boostField (_.decayedPopularity1) boostField ("recip(sqedist(%s,%s,lat,lng), 1, 5000, 1)".format(geoLat, geoLong)) boostQuery (_.name contains (lols, 10)) fetchFields (_.id, _.name, _.userid, _.mayorid, _.category_id_0, _.popularity, _.decayedPopularity1, _.lat, _.lng, _.checkin_info, _.score, _.hasSpecial, _.address, _.crossstreet, _.city, _.state, _.zip, _.country, _.checkinCount, _.partitionedPopularity)
    val qp = q.meta.queryParams(q).toList
    val expected = List("defType" -> "edismax",
      "q" -> "(holden's hobohut)",
      "start" -> "0",
      "pf" -> "text",
      "pf2" -> "text",
      "pf3" -> "text",
      "pf" -> "name^200.0",
      "pf2" -> "name^200.0",
      "pf3" -> "name^200.0",
      "pf" -> "aliases^25.0",
      "pf2" -> "aliases^25.0",
      "pf3" -> "aliases^25.0",
      "qf" -> "text",
      "qf" -> "ngram_name^0.2",
      "qf" -> "tags^0.01",
      "tieBreaker" -> "0.2",
      "fl" -> "id,name,userid,mayorid,category_id_0,popularity,decayedPopularity1,lat,lng,checkin_info,score,hasSpecial,address,crossstreet,city,state,zip,country,checkinCount,partitionedPopularity",
      "bq" -> "name:(holden's hobohut)^10.0",
      "bf" -> "recip(sqedist(%s,%s,lat,lng), 1, 5000, 1)".format(geoLat, geoLong),
      "bf" -> "decayedPopularity1",
      "rows" -> "10")
    Assert.assertEquals(Nil, ((qp.toSet &~ expected.toSet)).toList)
    Assert.assertEquals(Nil, (expected.toSet &~ qp.toSet).toList)
  }
  @Test
  def testAutoComplexQuery9 {
    val lols = "holden's hobohut"
    val geoLat = 37.7519528215759
    val geoLong = -122.42086887359619
    val q = SVenueTest where (_.default contains lols) useQueryType ("edismax") phraseBoost (_.text, 1) phraseBoost (_.name, 200) phraseBoost (_.aliases, 25) queryField (_.text) queryField (_.ngram_name, 0.2) queryField (_.tags, 0.01) tieBreaker (0.2) boostField (_.decayedPopularity1) boostField ("recip(sqedist(%s,%s,lat,lng), 1, 5000, 1)".format(geoLat, geoLong)) boostQuery (_.name contains (lols, 10)) fetchFields (_.id, _.name, _.userid, _.mayorid, _.category_id_0, _.popularity, _.decayedPopularity1, _.lat, _.lng, _.checkin_info, _.score, _.hasSpecial, _.address, _.crossstreet, _.city, _.state, _.zip, _.country, _.checkinCount, _.partitionedPopularity) filter (_.geo_s2_cell_ids inRadius (geoLat, geoLong, 1))
    val qp = q.meta.queryParams(q).toList
    val expected = List("defType" -> "edismax",
      "q" -> "(holden's hobohut)",
      "start" -> "0",
      "pf" -> "text",
      "pf2" -> "text",
      "pf3" -> "text",
      "pf" -> "name^200.0",
      "pf2" -> "name^200.0",
      "pf3" -> "name^200.0",
      "pf" -> "aliases^25.0",
      "pf2" -> "aliases^25.0",
      "pf3" -> "aliases^25.0",
      "qf" -> "text",
      "qf" -> "ngram_name^0.2",
      "qf" -> "tags^0.01",
      "fq" -> "geo_s2_cell_ids:(\"pleaseUseaRealGeoHash\" OR \"thisIsForFunctionalityTests\")",
      "tieBreaker" -> "0.2",
      "fl" -> "id,name,userid,mayorid,category_id_0,popularity,decayedPopularity1,lat,lng,checkin_info,score,hasSpecial,address,crossstreet,city,state,zip,country,checkinCount,partitionedPopularity",
      "bq" -> "name:(holden's hobohut)^10.0",
      "bf" -> "recip(sqedist(%s,%s,lat,lng), 1, 5000, 1)".format(geoLat, geoLong),
      "bf" -> "decayedPopularity1",
      "rows" -> "10")
    Assert.assertEquals(Nil, ((qp.toSet &~ expected.toSet)).toList)
    Assert.assertEquals(Nil, (expected.toSet &~ qp.toSet).toList)
  }
  @Test
  def testGeoDistQuery {
    val lols = "holden's hobohut"
    val geoLat = 37.7519528215759
    val geoLong = -122.42086887359619
    val q = SVenueTest where (_.default contains lols) useQueryType ("edismax") scoreBoostField (_.point recipSqeGeoDistance (geoLat, geoLong, 1, 5000, 1))
    val qp = q.meta.queryParams(q).toList
    val expected = List("defType" -> "edismax",
      "q" -> "(holden's hobohut)",
      "bf" -> "recip(sqedist(%s,%s,lat,lng),1,5000,1)".format(geoLat, geoLong),
      "start" -> "0",
      "rows" -> "10")
    Assert.assertEquals(Nil, ((qp.toSet &~ expected.toSet)).toList)
    Assert.assertEquals(Nil, (expected.toSet &~ qp.toSet).toList)
  }
  @Test
  def testAutoComplexQuery9b {
    val lols = "holden's hobohut"
    val geoLat = 37.7519528215759
    val geoLong = -122.42086887359619
    val q = SVenueTest where (_.default contains lols) useQueryType ("edismax") phraseBoost (_.text, 1) phraseBoost (_.name, 200) phraseBoost (_.aliases, 25) queryField (_.text) queryField (_.ngram_name, 0.2) queryField (_.tags, 0.01) tieBreaker (0.2) boostField (_.decayedPopularity1) scoreBoostField (_.point recipSqeGeoDistance (geoLat, geoLong, 1, 5000, 1)) boostQuery (_.name contains (lols, 10)) fetchFields (_.id, _.name, _.userid, _.mayorid, _.category_id_0, _.popularity, _.decayedPopularity1, _.lat, _.lng, _.checkin_info, _.score, _.hasSpecial, _.address, _.crossstreet, _.city, _.state, _.zip, _.country, _.checkinCount, _.partitionedPopularity) filter (_.geo_s2_cell_ids inRadius (geoLat, geoLong, 1))
    val qp = q.meta.queryParams(q).toList
    val expected = List("defType" -> "edismax",
      "q" -> "(holden's hobohut)",
      "start" -> "0",
      "pf" -> "text",
      "pf2" -> "text",
      "pf3" -> "text",
      "pf" -> "name^200.0",
      "pf2" -> "name^200.0",
      "pf3" -> "name^200.0",
      "pf" -> "aliases^25.0",
      "pf2" -> "aliases^25.0",
      "pf3" -> "aliases^25.0",
      "qf" -> "text",
      "qf" -> "ngram_name^0.2",
      "qf" -> "tags^0.01",
      "fq" -> "geo_s2_cell_ids:(\"pleaseUseaRealGeoHash\" OR \"thisIsForFunctionalityTests\")",
      "tieBreaker" -> "0.2",
      "fl" -> "id,name,userid,mayorid,category_id_0,popularity,decayedPopularity1,lat,lng,checkin_info,score,hasSpecial,address,crossstreet,city,state,zip,country,checkinCount,partitionedPopularity",
      "bq" -> "name:(holden's hobohut)^10.0",
      "bf" -> "recip(sqedist(%s,%s,lat,lng),1,5000,1)".format(geoLat, geoLong),
      "bf" -> "decayedPopularity1",
      "rows" -> "10")
    Assert.assertEquals(Nil, ((qp.toSet &~ expected.toSet)).toList)
    Assert.assertEquals(Nil, (expected.toSet &~ qp.toSet).toList)
  }
  @Test
  def testEventQuery1 {
    val geoLat = 37.7519528215759
    val geoLong = -122.42086887359619
    val lols = "DJ Hixxy"
    val q = SEventTest where (_.default contains lols) useQueryType ("edismax") filter (_.geo_s2_cell_ids inRadius (geoLat, geoLong, 1))
    val qp = q.meta.queryParams(q).toList
    val expected = List("defType" -> "edismax",
      "q" -> "(DJ Hixxy)",
      "start" -> "0",
      "rows" -> "10",
      "fq" -> "geo_s2_cell_ids:(\"pleaseUseaRealGeoHash\" OR \"thisIsForFunctionalityTests\")")
    Assert.assertEquals(Nil, ((qp.toSet &~ expected.toSet)).toList)
    Assert.assertEquals(Nil, (expected.toSet &~ qp.toSet).toList)
  }
  @Test
  def sortwithPopular {
    val lols = "holden's hobohut"
    val geoLat = 37.7519528215759
    val geoLong = -122.42086887359619
    val mcat = List("Coffee", "Delicious", "pirates")
    val q = SVenueTest where (_.meta_categories in mcat) useQueryType ("edismax") orderDesc (_.decayedPopularity1)
    val qp = q.meta.queryParams(q).toList
    val cat_str = mcat.map((x => "\"" + x + "\"")).mkString(" OR ")
    val expected = List("defType" -> "edismax",
      "sort" -> "decayedPopularity1 desc",
      "q" -> "meta_categories:(%s)".format(cat_str),
      "start" -> "0",
      "rows" -> "10")
    Assert.assertEquals(Nil, ((qp.toSet &~ expected.toSet)).toList)
    Assert.assertEquals(Nil, (expected.toSet &~ qp.toSet).toList)
  }
  @Test
  def sortwithPopularandLimit {
    val lols = "holden's hobohut"
    val geoLat = 37.7519528215759
    val geoLong = -122.42086887359619
    val mcat = List("Coffee", "Delicious", "pirates")
    val q = SVenueTest where (_.meta_categories in mcat) useQueryType ("edismax") orderDesc (_.decayedPopularity1) limit (200)
    val qp = q.meta.queryParams(q).toList
    val cat_str = mcat.map((x => "\"" + x + "\"")).mkString(" OR ")
    val expected = List("defType" -> "edismax",
      "sort" -> "decayedPopularity1 desc",
      "q" -> "meta_categories:(%s)".format(cat_str),
      "start" -> "0",
      "rows" -> "200")
    Assert.assertEquals(Nil, ((qp.toSet &~ expected.toSet)).toList)
    Assert.assertEquals(Nil, (expected.toSet &~ qp.toSet).toList)
  }
  @Test
  def sortwithPopularandLimitandCat {
    val lols = "holden's hobohut"
    val geoLat = 37.7519528215759
    val geoLong = -122.42086887359619
    val cat = List("test")
    val q = SVenueTest where (_.category_ids in cat) useQueryType ("edismax") orderDesc (_.decayedPopularity1) limit (200)
    val qp = q.meta.queryParams(q).toList
    val cat_str = cat.map((x => "\"" + x + "\"")).mkString(" OR ")
    val expected = List("defType" -> "edismax",
      "sort" -> "decayedPopularity1 desc",
      "q" -> "category_ids:(%s)".format(cat_str),
      "start" -> "0",
      "rows" -> "200")
    Assert.assertEquals(Nil, ((qp.toSet &~ expected.toSet)).toList)
    Assert.assertEquals(Nil, (expected.toSet &~ qp.toSet).toList)
  }

  @Test
  def sortwithPopularandLimitandOr {
    val lols = "holden's hobohut"
    val geoLat = 37.7519528215759
    val geoLong = -122.42086887359619
    val mcat = List("Coffee", "Delicious", "pirates")
    val cat = List("test")
    val q = SVenueTest where (_.category_ids in cat) or (_.meta_categories in mcat) useQueryType ("edismax") orderDesc (_.decayedPopularity1) limit (200)
    val qp = q.meta.queryParams(q).toList
    val mcat_str = mcat.map((x => "\"" + x + "\"")).mkString(" OR ")
    val cat_str = cat.map((x => "\"" + x + "\"")).mkString(" OR ")
    val expected = List("defType" -> "edismax",
      "sort" -> "decayedPopularity1 desc",
      "q" -> "(meta_categories:(%s)) OR (category_ids:(%s))".format(mcat_str, cat_str),
      "start" -> "0",
      "rows" -> "200")
    Assert.assertEquals(Nil, ((qp.toSet &~ expected.toSet)).toList)
    Assert.assertEquals(Nil, (expected.toSet &~ qp.toSet).toList)
  }

  @Test
  def sortwithPopularandAll {
    val geoLat = 37.7519528215759
    val geoLong = -122.42086887359619
    val q = SVenueTest where (_.metall any) useQueryType ("edismax") orderDesc (_.decayedPopularity1)
    val qp = q.meta.queryParams(q).toList
    val expected = List("defType" -> "edismax",
      "sort" -> "decayedPopularity1 desc",
      "q" -> "*:*",
      "start" -> "0",
      "rows" -> "10")
    Assert.assertEquals(Nil, ((qp.toSet &~ expected.toSet)).toList)
    Assert.assertEquals(Nil, (expected.toSet &~ qp.toSet).toList)
  }

}