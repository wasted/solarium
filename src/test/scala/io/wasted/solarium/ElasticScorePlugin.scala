// Copyright 2011-2012 Foursquare Labs Inc. All Rights Reserved.
// Copyright (c) 2014, 2015, wasted.io Ltd.

package io.wasted.solarium

import org.elasticsearch.common.geo.GeoDistance
import org.elasticsearch.common.unit.DistanceUnit
import org.elasticsearch.index.fielddata.AtomicNumericFieldData
import org.elasticsearch.index.fielddata.plain.GeoPointDoubleArrayAtomicFieldData
import org.elasticsearch.plugins.AbstractPlugin
import org.elasticsearch.script.ScriptModule
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.xcontent.support.XContentMapValues
import org.elasticsearch.script.AbstractFloatSearchScript
import org.elasticsearch.script.ExecutableScript
import org.elasticsearch.script.NativeScriptFactory
import org.elasticsearch.search.lookup.DocLookup

import java.util.Map

/**
 * Note: assumes that the point field is point
 */
/*
case class CombinedDistanceDocumentScorerSearchScript(val lat: Double,
                                                      val lon: Double,
                                                      val weight1: Float,
                                                      val weight2: Float,
                                                      val weight3: Float) extends AbstractFloatSearchScript {

  override def runAsFloat(): Float = {
    val myDoc: DocLookup = doc();
    val point: GeoPointDoubleArrayAtomicFieldData.Single = myDoc.get("point").asInstanceOf[GeoPointDoubleArrayAtomicFieldData.Single];
    val popularity: Double = myDoc.get("decayedPopularity1").asInstanceOf[AtomicNumericFieldData].getDoubleValue()
    // up to you to remove score from here or not..., also, possibly, add more weights options
    val geosource = new GeoDistance.ArcFixedSourceDistance(lat, lon, DistanceUnit.KILOMETERS)
    val distance = GeoDistance.distanceValues(point.getGeoPointValues, geosource)

    val myScore: Float = weight3 * (score() * (1 + weight1 * math.pow(1.0 * (math.pow(distance.valueAt(0), 2.0) + 1.0), -1.0) + popularity * weight2)).toFloat
    myScore
  }
}

class ScoreFactory extends NativeScriptFactory {
  def newScript(@Nullable params: Map[String, Object]): ExecutableScript = {
    val lat: Double = if (params == null) 1 else XContentMapValues.nodeDoubleValue(params.get("lat"), 0);
    val lon: Double = if (params == null) 1 else XContentMapValues.nodeDoubleValue(params.get("lon"), 0);
    val weight1: Float = if (params == null) 1 else XContentMapValues.nodeFloatValue(params.get("weight1"), 5000.0f);
    val weight2: Float = if (params == null) 1 else XContentMapValues.nodeFloatValue(params.get("weight2"), 0.05f);
    val weight3: Float = if (params == null) 1 else XContentMapValues.nodeFloatValue(params.get("weight3"), 1.00f);
    return new CombinedDistanceDocumentScorerSearchScript(lat, lon, weight1, weight2, weight3);
  }
}
*/
/**
 * Provides a fast* score script for our primary use case
 */
/*
class ElasticScorePlugin extends AbstractPlugin {
  override def name(): String = "foursquare";

  override def description(): String = "foursquare plugin";

  def onModule(module: ScriptModule): Unit = {
    module.registerScript("distance_score_magic", classOf[ScoreFactory]);
  }
} */ 