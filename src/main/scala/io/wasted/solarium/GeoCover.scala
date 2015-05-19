// Copyright 2011-2012 Foursquare Labs Inc. All Rights Reserved.
// Copyright (c) 2014, 2015, wasted.io Ltd.

package io.wasted.solarium

trait GeoCover {
  def boundsCoverString(maxCells: Int = 0, minLevel: Int = 0, maxLevel: Int = 0): Seq[String]
}