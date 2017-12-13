package org.apache.spark.ml.gbm

import scala.collection.mutable
import scala.reflect.ClassTag
import scala.{specialized => spec}

import org.apache.spark.internal.Logging
import org.apache.spark.ml.linalg._
import org.apache.spark.rdd.RDD


private[gbm] object Tree extends Logging {

  /**
    * Implementation of training a new tree
    *
    * @param data        instances containing (grad, hess, bins)
    * @param boostConfig boosting configuration
    * @param treeConfig  tree growing configuration
    * @tparam H type of gradient and hessian
    * @tparam B type of bin
    * @return a new tree if any
    */
  def train[H: Numeric : ClassTag : FromDouble, B: Integral : ClassTag](data: RDD[(H, H, BinVector[B])],
                                                                        boostConfig: BoostConfig,
                                                                        treeConfig: TreeConfig): Option[TreeModel] = {
    val sc = data.sparkContext
    Split.registerKryoClasses(sc)

    data.persist(boostConfig.getStorageLevel)

    var nodeIds = sc.emptyRDD[Long]
    val nodesCheckpointer = new Checkpointer[Long](sc,
      boostConfig.getCheckpointInterval, boostConfig.getStorageLevel)

    var hists = sc.emptyRDD[((Long, Int), Array[H])]
    val histsCheckpointer = new Checkpointer[((Long, Int), Array[H])](sc,
      boostConfig.getCheckpointInterval, boostConfig.getStorageLevel)

    val logPrefix = s"Iter ${treeConfig.iteration}: Tree ${treeConfig.treeIndex}:"
    logInfo(s"$logPrefix tree building start")


    val root = LearningNode.create(1L)

    val lastSplits = mutable.Map[Long, Split]()

    var minNodeId = 1L
    var numLeaves = 1L
    var finished = false

    while (!finished) {
      val start = System.nanoTime

      val depth = root.subtreeDepth
      logInfo(s"$logPrefix Depth $depth: splitting start, parallelism ${sc.defaultParallelism}")

      if (minNodeId == 1L) {
        nodeIds = data.map(_ => 1L)
      } else {
        nodeIds = updateNodeIds[H, B](data, nodeIds, lastSplits.toMap)
      }
      nodesCheckpointer.update(nodeIds)

      if (minNodeId == 1L) {
        // direct compute the histogram of root node
        hists = computeHists[H, B](data.zip(nodeIds), minNodeId, treeConfig.numCols, sc.defaultParallelism)

      } else {
        // compute the histogram of right leaves
        val rightHists = computeHists[H, B](data.zip(nodeIds), minNodeId, treeConfig.numCols, sc.defaultParallelism)

        // pre-compute an even split of (nodeId, col) pairs
        val pairSplits = computePairSplits(lastSplits.keys.toArray, treeConfig.numCols, sc.defaultParallelism)

        // compute the histogram of both left leaves and right leaves by subtraction
        hists = subtractHists[H](hists, rightHists, boostConfig.getMinNodeHess, sc.defaultParallelism, pairSplits)
      }
      histsCheckpointer.update(hists)

      // find best splits
      val splits = findSplits[H](hists, boostConfig, treeConfig,
        boostConfig.getSeed + treeConfig.treeIndex + depth)

      if (splits.isEmpty) {
        logInfo(s"$logPrefix Depth $depth: no more splits found, tree building finished")
        finished = true

      } else if (numLeaves + splits.size > boostConfig.getMaxLeaves) {
        logInfo(s"$logPrefix Depth $depth: maxLeaves=${boostConfig.getMaxLeaves} reached, tree building finished")
        finished = true

      } else {
        logInfo(s"$logPrefix Depth $depth: splitting finished, ${splits.size}/$numLeaves leaves split, " +
          s"gain=${splits.values.map(_.gain).sum}, duration ${(System.nanoTime - start) / 1e9} seconds")

        numLeaves += splits.size

        // update tree
        updateTree(root, minNodeId, splits)

        // update last splits
        lastSplits.clear()
        splits.foreach { case (nodeId, split) =>
          lastSplits.update(nodeId, split)
        }
      }

      if (root.subtreeDepth >= boostConfig.getMaxDepth) {
        logInfo(s"$logPrefix maxDepth=${boostConfig.getMaxDepth} reached, tree building finished")
        finished = true
      }
      if (numLeaves >= boostConfig.getMaxLeaves) {
        logInfo(s"$logPrefix maxLeaves=${boostConfig.getMaxLeaves} reached, tree building finished")
        finished = true
      }

      minNodeId <<= 1
    }
    logInfo(s"$logPrefix tree building finished")

    data.unpersist(blocking = false)
    nodesCheckpointer.deleteAllCheckpoints()
    nodesCheckpointer.unpersistDataSet()
    histsCheckpointer.deleteAllCheckpoints()
    histsCheckpointer.unpersistDataSet()

    if (root.subtreeDepth > 0) {
      Some(TreeModel.createModel(root, boostConfig, treeConfig))
    } else {
      None
    }
  }


  /**
    * update tree
    *
    * @param root      root of tree
    * @param minNodeId minimum nodeId for this level
    * @param splits    splits of leaves
    */
  def updateTree(root: LearningNode,
                 minNodeId: Long,
                 splits: Map[Long, Split]): Unit = {
    val nodes = root.nodeIterator.filter { node =>
      node.nodeId >= minNodeId && splits.contains(node.nodeId)
    }.toArray

    nodes.foreach { node =>
      node.isLeaf = false
      node.split = splits.get(node.nodeId)

      val leftId = node.nodeId << 1
      node.leftNode = Some(LearningNode.create(leftId))
      node.leftNode.get.prediction = node.split.get.leftWeight

      val rightId = leftId + 1
      node.rightNode = Some(LearningNode.create(rightId))
      node.rightNode.get.prediction = node.split.get.rightWeight
    }
  }


  /**
    * update nodeIds
    *
    * @param data    instances containing (grad, hess, bins)
    * @param nodeIds previous nodeIds
    * @param splits  splits found in the last round
    * @tparam H
    * @tparam B
    * @return updated nodeIds
    */
  def updateNodeIds[H: Numeric : ClassTag, B: Integral : ClassTag](data: RDD[(H, H, BinVector[B])],
                                                                   nodeIds: RDD[Long],
                                                                   splits: Map[Long, Split]): RDD[Long] = {
    data.zip(nodeIds).map {
      case ((_, _, bins), nodeId) =>
        val split = splits.get(nodeId)
        if (split.nonEmpty) {
          val leftNodeId = nodeId << 1
          if (split.get.goLeft[B](bins)) {
            leftNodeId
          } else {
            leftNodeId + 1
          }
        } else {
          nodeId
        }
    }
  }


  /**
    * Compute the histogram of root node or the right leaves with nodeId greater than minNodeId
    *
    * @param data        instances appended with nodeId, containing ((grad, hess, bins), nodeId)
    * @param minNodeId   minimum nodeId for this level
    * @param numCols     number of columns
    * @param parallelism parallelism
    * @tparam H
    * @tparam B
    * @return histogram data containing (nodeId, columnId, histogram)
    */
  def computeHists[H: Numeric : ClassTag, B: Integral : ClassTag](data: RDD[((H, H, BinVector[B]), Long)],
                                                                  minNodeId: Long,
                                                                  numCols: Int,
                                                                  parallelism: Int): RDD[((Long, Int), Array[H])] = {

    val intB = implicitly[Integral[B]]
    val numH = implicitly[Numeric[H]]

    val partitioner1 = new GBMHashPartitioner(partitions = parallelism,
      by = { (a: Any) =>
        val t = a.asInstanceOf[(Long, Int, _)]
        (t._1, t._2)
      })

    val partitioner2 = new GBMHashPartitioner(partitions = parallelism)

    data.filter { case (_, nodeId) =>
      // root or right leaves
      nodeId >= minNodeId && nodeId % 2 == 1L

    }.flatMap { case ((grad, hess, bins), nodeId) =>
      // sum of hist of each node, with col=-1
      Iterator.single((nodeId, -1, intB.zero), (grad, hess)) ++
        // ignore zero-index bins
        bins.activeIter.map { case (col, bin) =>
          ((nodeId, col, bin), (grad, hess))
        }

    }.reduceByKey(
      // aggregate by (nodeId, col, bin), and partition by (nodeId, col)
      partitioner = partitioner1,
      func = {
        case ((grad1, hess1), (grad2, hess2)) =>
          (numH.plus(grad1, grad2), numH.plus(hess1, hess2))

      }).flatMap { case ((nodeId, col, bin), (grad, hess)) =>
      if (col != -1) {
        require(!intB.equiv(bin, intB.zero))
        Iterator.single(((nodeId, col), (bin, grad, hess)))
      } else {
        require(intB.equiv(bin, intB.zero))
        Iterator.range(0, numCols).map(c => ((nodeId, c), (bin, grad, hess)))
      }

      // group by (nodeId, col), while keeping the partitioning (except items with bin=zero)
    }.groupByKey(partitioner2)

      .map { case ((nodeId, col), iter) =>
        val seq = iter.toSeq
        val maxBin = seq.map(_._1).max
        val len = (intB.toInt(maxBin) + 1) << 1

        val hist = Array.fill(len)(numH.zero)
        var i = 0
        while (i < seq.length) {
          val (bin, grad, hess) = seq(i)

          val index = intB.toInt(bin) << 1

          // zero-index bin stores the sum of hist
          if (index == 0) {
            hist(0) = numH.plus(hist(0), grad)
            hist(1) = numH.plus(hist(1), hess)
          } else {
            hist(index) = grad
            hist(index + 1) = hess
            hist(0) = numH.minus(hist(0), grad)
            hist(1) = numH.minus(hist(1), hess)
          }

          i += 1
        }

        ((nodeId, col), hist)
      }
  }


  /**
    * Histogram subtraction
    *
    * @param nodeHists   histogram data of parent nodes
    * @param rightHists  histogram data of right leaves
    * @param minNodeHess minimum hess needed for a node
    * @param parallelism parallelism
    * @param pairSplits  pre-computed even splits of (nodeId, col) pairs
    * @tparam H
    * @return histogram data of both left and right leaves
    */
  def subtractHists[H: Numeric : ClassTag](nodeHists: RDD[((Long, Int), Array[H])],
                                           rightHists: RDD[((Long, Int), Array[H])],
                                           minNodeHess: Double,
                                           parallelism: Int,
                                           pairSplits: Array[(Long, Int)]): RDD[((Long, Int), Array[H])] = {
    val numH = implicitly[Numeric[H]]

    val partitioner = if (pairSplits.isEmpty) {
      new GBMHashPartitioner(parallelism)
    } else {
      new GBMRangePartitioner[(Long, Int)](pairSplits)
    }

    rightHists.map { case ((rightNode, col), hist) =>
      ((rightNode >> 1, col), hist)

    }.join(nodeHists, partitioner)

      .flatMap { case ((nodeId, col), (rightHist, nodeHist)) =>
        require(rightHist.length <= nodeHist.length)

        var i = 0
        while (i < rightHist.length) {
          nodeHist(i) = numH.minus(nodeHist(i), rightHist(i))
          i += 1
        }

        val leftNode = nodeId << 1
        val rightNode = leftNode + 1

        ((leftNode, col), nodeHist) ::
          ((rightNode, col), rightHist) :: Nil

      }.filter { case ((_, _), hist) =>

      var hessSum = 0.0
      var nnz = 0
      var i = 0
      while (i < hist.length) {
        if (!numH.equiv(hist(i), numH.zero) ||
          !numH.equiv(hist(i + 1), numH.zero)) {
          hessSum += numH.toDouble(hist(i + 1))
          nnz += 1
        }
        i += 2
      }

      // leaves with hess less than minNodeHess * 2 can not grow furthermore
      nnz >= 2 && hessSum >= minNodeHess * 2
    }
  }


  /**
    * Search the optimal splits on each leaves
    *
    * @param nodeHists   histogram data of leaves nodes
    * @param boostConfig boosting configuration
    * @param treeConfig  tree growing configuration
    * @param seed        random seed for column sampling by level
    * @tparam H
    * @return optimal splits for each node
    */
  def findSplits[H: Numeric : ClassTag](nodeHists: RDD[((Long, Int), Array[H])],
                                        boostConfig: BoostConfig,
                                        treeConfig: TreeConfig,
                                        seed: Long): Map[Long, Split] = {
    val sc = nodeHists.sparkContext
    val acc = sc.longAccumulator("NumTrials")

    // column sampling by level
    val sampledHists = if (boostConfig.getColSampleByLevel == 1) {
      nodeHists
    } else {
      nodeHists.sample(false, boostConfig.getColSampleByLevel, seed)
    }

    val splits = sampledHists.flatMap {
      case ((nodeId, col), hist) =>
        acc.add(1L)
        val split = Split.split[H](col, hist, boostConfig, treeConfig)
        split.map((nodeId, _))

    }.mapPartitions { it =>
      val splits = mutable.Map[Long, Split]()
      it.foreach { case (nodeId, split) =>
        val s = splits.get(nodeId)
        if (s.isEmpty || split.gain > s.get.gain) {
          splits.update(nodeId, split)
        }
      }
      Iterator.single(splits.toArray)

    }.treeReduce(
      f = {
        case (splits1, splits2) =>
          (splits1 ++ splits2).groupBy(_._1)
            .map { case (nodeId, splits) =>
              (nodeId, splits.map(_._2).maxBy(_.gain))
            }.toArray
      }, depth = boostConfig.getAggregationDepth)

    logInfo(s"${acc.value} trials to find best splits of ${splits.length} nodes")
    splits.toMap
  }


  /**
    * Since the (nodeId, columnId) candidate pairs to search optimum split are known before computation
    * we can partition them in a partial sorted way to reduce the communication overhead in following aggregation
    *
    * @param nodeIds     splitted nodeIds in the last level
    * @param numCols     number of columns
    * @param parallelism parallelism
    * @return even splits of pairs
    */
  def computePairSplits(nodeIds: Array[Long],
                        numCols: Int,
                        parallelism: Int): Array[(Long, Int)] = {
    require(parallelism > 0)

    // leaves in current level
    val leaves = nodeIds.flatMap { nodeId =>
      val leftNodeId = nodeId << 1
      Seq(leftNodeId, leftNodeId + 1)
    }.sorted

    val numLeaves = leaves.length

    if (leaves.isEmpty || parallelism == 1) {
      Array.empty

    } else {

      val step = numLeaves.toDouble / parallelism

      // parallelism - 1 splitting points
      Array.range(1, parallelism).map { i =>
        val p = i * step
        val n = p.toInt
        val b = p - n
        val c = (b * numCols).round.toInt
        (leaves(n), c)
      }.distinct.sorted
    }
  }
}


class TreeModel(val root: Node) extends Serializable {

  lazy val depth: Int = root.subtreeDepth

  lazy val numLeaves: Long = root.numLeaves

  lazy val numNodes: Long = root.numDescendants

  private[gbm] def predict[@spec(Byte, Short, Int) B: Integral](bins: BinVector[B]): Double = root.predict[B](bins)

  def predict(vec: Vector, discretizer: Discretizer): Double = root.predict(vec, discretizer)

  private[gbm] def index[@spec(Byte, Short, Int) B: Integral](bins: BinVector[B]): Long = root.index[B](bins)

  def index(vec: Vector, discretizer: Discretizer): Long = root.index(vec, discretizer)

  def computeImportance: Map[Int, Double] = {
    val gains = collection.mutable.Map[Int, Double]()
    root.nodeIterator.foreach {
      case n: InternalNode =>
        val gain = gains.getOrElse(n.featureId, 0.0)
        gains.update(n.featureId, gain + n.gain)

      case _ =>
    }

    gains.toMap
  }
}


private[gbm] object TreeModel {

  def createModel(root: LearningNode,
                  boostMeta: BoostConfig,
                  treeMeta: TreeConfig): TreeModel = {
    val leafIds = root.nodeIterator.filter(_.isLeaf).map(_.nodeId).toArray.sorted
    val node = TreeModel.createNode(root, treeMeta.columns, leafIds)
    new TreeModel(node)
  }


  def createNode(node: LearningNode,
                 columns: Array[Int],
                 leafIds: Array[Long]): Node = {

    if (node.isLeaf) {
      require(node.leftNode.isEmpty &&
        node.rightNode.isEmpty &&
        node.split.isEmpty)

      val leafId = leafIds.indexOf(node.nodeId)
      new LeafNode(node.prediction, leafId)

    } else {
      require(node.split.nonEmpty &&
        node.leftNode.nonEmpty && node.rightNode.nonEmpty)

      val reindex = columns(node.split.get.featureId)

      node.split.get match {
        case s: SeqSplit =>
          new InternalNode(reindex, true, s.missingGoLeft,
            Array(s.threshold), s.gain,
            createNode(node.leftNode.get, columns, leafIds),
            createNode(node.rightNode.get, columns, leafIds))

        case s: SetSplit =>
          new InternalNode(reindex, false, s.missingGoLeft,
            s.leftSet, s.gain,
            createNode(node.leftNode.get, columns, leafIds),
            createNode(node.rightNode.get, columns, leafIds))
      }
    }
  }
}
