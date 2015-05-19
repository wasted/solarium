// Copyright 2012 Foursquare Labs Inc. All Rights Reserved.
// Copyright (c) 2014, 2015, wasted.io Ltd.

package io.wasted.solarium

import Ast._
import io.wasted.solarium.Ast.AbstractClause

object Optimizer {

  def optimizeFilters(filters: List[AbstractClause]): List[AbstractClause] = {
    filters.filter {
      //Remove all empty search clauses
      case Clause("*", Splat(), true) => false
      case Clause("_all", Splat(), true) => false
      case _ => true
    }
  }
  def optimizeQuery(clause: AbstractClause): AbstractClause = {
    clause
  }
  def optimizeBoosts(boostQueries: List[AbstractClause]): List[AbstractClause] = {
    boostQueries
  }

}
