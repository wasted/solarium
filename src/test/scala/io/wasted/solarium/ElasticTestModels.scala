// Copyright 2011-2012 Foursquare Labs Inc. All Rights Reserved.
// Copyright (c) 2014, 2015, wasted.io Ltd.

package io.wasted.solarium

object ESimplePanda extends ESimplePanda with ElasticMeta[ESimplePanda] {
  //Force local for testing
  override val useTransport = false
  override val clusterName = "simpletest" //Override me knthx
  override val indexName = "esimplepanda"
}
class ESimplePanda extends ElasticSchema[ESimplePanda] {
  def meta = ESimplePanda
  object default extends SlashemDefaultStringField(this)
  object id extends SlashemObjectIdField(this)
  object name extends SlashemStringField(this)
  object hobos extends SlashemStringField(this)
  object score extends SlashemDoubleField(this)
  object magic extends SlashemStringField(this)
  object followers extends SlashemIntField(this)
  object foreign extends SlashemStringField(this)
  object favnums extends SlashemIntListField(this)
  object nicknames extends SlashemStringListField(this)
  object nicknamesString extends SlashemStringField(this)
  object hugenums extends SlashemLongListField(this)
  object termsfield extends SlashemUnanalyzedStringField(this)
  object favvenueids extends SlashemObjectIdListField(this)
}

object ESimpleGeoPanda extends ESimpleGeoPanda with ElasticMeta[ESimpleGeoPanda] {
  //Force local for testing
  override val useTransport = false
  override val clusterName = "simpletest" //Override me knthx
  override val indexName = "geopanda"
}
class ESimpleGeoPanda extends ElasticSchema[ESimpleGeoPanda] {
  def meta = ESimpleGeoPanda
  //We use "_all" in ES rather than "*" as in Solr to query all fields
  object metall extends SlashemStringField(this) {
    override def name = "_all"
  }
  object default extends SlashemDefaultStringField(this)
  object id extends SlashemObjectIdField(this)
  object name extends SlashemStringField(this)
  object score extends SlashemDoubleField(this)
  object point extends SlashemPointField(this)
  object decayedPopularity1 extends SlashemDoubleField(this)
}