// Copyright 2011-2012 Foursquare Labs Inc. All Rights Reserved.
// Copyright (c) 2014, 2015, wasted.io Ltd.

package io.wasted.solarium

import org.junit.Test
import org.junit._

import org.scalatest.junit.JUnitSuite

class OptimizerTest extends JUnitSuite {

  @Test
  def testProduceCorrectListfieldFilterAny {
    val q = SVenueTest where (_.metall any) filter (_.metall any)
    val optimizedQ = q.optimize()
    val qp = q.meta.queryParams(optimizedQ).toList
    Assert.assertEquals(qp.sortWith(_._1 > _._1),
      List("q" -> "*:*",
        "start" -> "0",
        "rows" -> "10").sortWith(_._1 > _._1))
  }

}