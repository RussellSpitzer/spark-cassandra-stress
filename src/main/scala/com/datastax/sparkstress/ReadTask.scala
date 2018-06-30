package com.datastax.sparkstress

import java.sql.Timestamp
import java.util.UUID

import org.apache.spark.sql.{DataFrame, SparkSession}
import com.datastax.spark.connector.cql.CassandraConnector
import com.datastax.sparkstress.RowTypes.PerfRowClass
import com.datastax.spark.connector._
import com.datastax.sparkstress.SparkStressImplicits._
import org.joda.time.DateTime
import org.apache.spark.sql.cassandra._
import org.apache.spark.sql.functions._
import SaveMethod._
import DistributedDataType._

import scala.collection.mutable
import scala.util.Random

abstract class ReadTask(config: Config, ss: SparkSession) extends StressTask {

  val sc = ss.sparkContext
  val uuidPivot = UUID.fromString("9b657ca1-bfb1-49c0-85f5-04b127adc6f3")
  val timePivot = new DateTime(2000, 1, 1, 0, 0, 0, 0).plusSeconds(500)
  val keyspace = config.keyspace
  val table = config.table

  val numberNodes = CassandraConnector(sc.getConf).withClusterDo(_.getMetadata.getAllHosts.size)
  val tenthKeys: Int = config.numTotalKeys.toInt / 10

  val cores = sys.env.getOrElse("SPARK_WORKER_CORES", "1").toInt * numberNodes
  val defaultParallelism = math.max(sc.defaultParallelism, cores)
  val coresPerNode: Int = defaultParallelism / numberNodes

  final def run(): Long = {
    val count = performTask()
    println(s"Loaded $count rows")
    count
  }

  def performTask(): Long

  def runTrials(ss: SparkSession): Seq[TestResult] = {
    println("About to Start Trials")
    for (trial <- 1 to config.trials) yield {
      TestResult(time(run()), 0L)
    }
  }

  def getDataFrame(): DataFrame = {
    config.saveMethod match {
      // regular read method from DSE/Cassandra
      case Driver => ss
        .read
        .cassandraFormat(table, keyspace)
        .load()
      // filesystem read methods
      case _ => ss.read.format(config.saveMethod.toString).load(s"dsefs:///${keyspace}.${table}")
    }
  }

  def readColumns(columnNames: Seq[String]): Long = {
    val columns: Seq[org.apache.spark.sql.Column] = columnNames.map(col(_))
    getDataFrame().select(columns:_*).rdd.count
  }
}

/**
  * Full Table Scan Two Columns using DataSets
  * Performs a full table scan retrieving two columns from the underlying
  * table.
  */
@ReadTest
class FTSTwoColumns(config: Config, ss: SparkSession) extends ReadTask(config, ss) {
  override def performTask(): Long = {
    config.distributedDataType match {
      case RDD => sc.cassandraTable[String](keyspace, table).select("color", "size").count
      case DataFrame => readColumns(Seq("color", "size"))
    }
  }
}

/**
  * Full Table Scan Three Columns using DataSets
  * Performs a full table scan but only retrieves a single column from the underlying
  * table.
  */
@ReadTest
class FTSThreeColumns(config: Config, ss: SparkSession) extends ReadTask(config, ss) {
  override def performTask(): Long = {
    config.distributedDataType match {
      case RDD => sc.cassandraTable[String](keyspace, table)
        .select("color", "size", "qty")
        .count
      case DataFrame => readColumns(Seq("color", "size", "qty"))
    }
  }
}

/**
  * Full Table Scan Four Columns using DataSets
  * Performs a full table scan but only retrieves a single column from the underlying
  * table.
  */
@ReadTest
class FTSFourColumns(config: Config, ss: SparkSession) extends ReadTask(config, ss) {
  override def performTask(): Long = {
    config.distributedDataType match {
      case RDD => sc.cassandraTable[String](keyspace, table).select("color", "size", "qty",
        "order_number").count
      case DataFrame => readColumns(Seq("color", "size", "qty", "order_number"))
    }
  }
}

/**
  * Push Down Count
  * Uses our internally cassandra count pushdown, this means all of the aggregation
  * is done on the C* side
  */
@ReadTest
class PDCount(config: Config, ss: SparkSession) extends ReadTask(config, ss) {
  override def performTask(): Long = config.distributedDataType match {
    case RDD => sc.cassandraTable(keyspace, table).cassandraCount()
    case DataFrame => getDataFrame().count
  }
}

/**
  * Full Table Scan One Column
  * Performs a full table scan but only retrieves a single column from the underlying
  * table.
  */
@ReadTest
class FTSOneColumn(config: Config, ss: SparkSession) extends ReadTask(config, ss) {
  override def performTask() = {
    config.distributedDataType match {
      case RDD => sc.cassandraTable[String](keyspace, table).select("color").count
      case DataFrame => readColumns(Seq("color"))
    }
  }
}

/**
  * Full Table Scan One Column
  * Performs a full table scan but only retrieves all columns from the underlying table.
  */
@ReadTest
class FTSAllColumns(config: Config, ss: SparkSession) extends ReadTask(config, ss) {
  override def performTask(): Long = {
    config.distributedDataType match {
      case RDD => sc.cassandraTable[PerfRowClass](keyspace, table).count
      case DataFrame => readColumns(Seq("order_number", "qty", "color", "size", "order_time",
        "store"))
    }
  }
}

/**
  * Full Table Scan Five Columns
  * Performs a full table scan and only retrieves 5 of the columns for each row
  */
@ReadTest
class FTSFiveColumns(config: Config, ss: SparkSession) extends ReadTask(config, ss) {
  override def performTask(): Long = {
    config.distributedDataType match {
      case RDD =>
        sc.cassandraTable[(UUID, Int, String, String, org.joda.time.DateTime)](keyspace,
          table)
          .select("order_number", "qty", "color", "size", "order_time")
          .count
      case DataFrame => readColumns(Seq("order_number", "qty", "color", "size", "order_time"))
    }
  }
}

/**
  * Full Table Scan with a Clustering Column Predicate Pushed down to C*
  */
@ReadTest
class FTSPDClusteringAllColumns(config: Config, ss: SparkSession) extends ReadTask(config, ss) {
  override def performTask(): Long = {
    config.distributedDataType match {
      case RDD =>
        sc.cassandraTable[PerfRowClass](keyspace, table)
          .where("order_time < ?", timePivot)
          .count
      case DataFrame =>
        getDataFrame()
          .filter(col("order_time") < lit(new Timestamp(timePivot.getMillis)))
          .rdd
          .count
    }
  }
}

/**
  * Full Table Scan with a Clustering Column Predicate Pushed down to C*
  * Only 5 columns retrieved per row
  */
@ReadTest
class FTSPDClusteringFiveColumns(config: Config, ss: SparkSession) extends ReadTask(config, ss) {
  override def performTask(): Long = config.distributedDataType match {
      case RDD =>
        sc.cassandraTable[(UUID, Int, String, String, org.joda.time.DateTime)](keyspace, table)
          .where("order_time < ?", timePivot)
          .select("order_number", "qty", "color", "size", "order_time")
          .count
      case DataFrame =>
        getDataFrame()
          .filter(col("order_time") < lit(new Timestamp(timePivot.getMillis)))
          .select("order_number", "qty", "color", "size", "order_time")
          .rdd
          .count
  }
}

/**
  * Join With C* with 1M Partition Key requests
  */
@ReadTest
class JWCAllColumns(config: Config, ss: SparkSession) extends ReadTask(config, ss) {
  override def performTask(): Long = config.distributedDataType match {
    case RDD =>
      sc.parallelize(1 to tenthKeys)
        .map(num => Tuple1(s"Store $num"))
        .joinWithCassandraTable[PerfRowClass](keyspace, table)
        .count
    case DataFrame =>
      val joinTarget = getDataFrame()
      ss
        .range(1, tenthKeys)
        .select(concat(lit("Store "), col("id")).as("key"))
        .join(joinTarget, joinTarget("store") === col("key"))
        .rdd
        .count
  }
}

/**
  * Join With C* with 1M Partition Key requests
  * A repartitionByCassandraReplica occurs before retrieving the data
  */
@ReadTest
class JWCRPAllColumns(config: Config, ss: SparkSession) extends
  ReadTask(config, ss) {
  override def performTask(): Long = config.distributedDataType match {
    case RDD =>
      sc.parallelize(1 to tenthKeys)
        .map(num => Tuple1(s"Store $num"))
        .repartitionByCassandraReplica(keyspace, table, coresPerNode)
        .joinWithCassandraTable[PerfRowClass](keyspace, table)
        .count
    case DataFrame => throw new IllegalArgumentException("This test is not supported with the Dataset API")
  }
}

/**
  * Join With C* with 1M Partition Key requests
  * A clustering column predicate is pushed down to limit data retrevial
  */
@ReadTest
class JWCPDClusteringAllColumns(config: Config, ss: SparkSession) extends ReadTask(config, ss) {
  def performTask: Long = config.distributedDataType match {
    case RDD =>
      sc.parallelize(1 to tenthKeys)
        .map(num => Tuple1(s"Store $num"))
        .joinWithCassandraTable[PerfRowClass](keyspace, table)
        .where("order_time < ?", timePivot)
        .count
    case DataFrame =>
      val joinTarget = getDataFrame()
        .filter(col("order_time") < lit(new Timestamp(timePivot.getMillis)))
      ss
        .range(1, tenthKeys)
        .select(concat(lit("Store "), col("id")).as("key"))
        .join(joinTarget, joinTarget("store") === col("key"))
        .rdd
        .count

  }
}

/**
  * A single C* partition is retrieved in an RDD
  */
@ReadTest
class RetrieveSinglePartition(config: Config, ss: SparkSession) extends ReadTask(config, ss) {
  override def performTask(): Long = config.distributedDataType match {
    case RDD =>
      sc.cassandraTable[String](keyspace, table)
        .where("store = ? ", "Store 5")
        .count
    case DataFrame =>
      getDataFrame()
        .filter(col("store") === "Store 5")
        .rdd
        .count
  }
}

/**
  * Select with "in" clause with `inKeysCount` keys.
  * Uses writewiderowbypartition table.
  */
abstract class AbstractInClauseSelect(config: Config, ss: SparkSession, inKeysCount: Int)
  extends ReadTask(config, ss) {

  private val random = new Random(config.seed)

  private def uniqueKeys(r: Random, upperBound: Long, count: Int): Seq[Long] = {
    val result = mutable.Set[Long]()
    while (result.size != count) {
      result.add(Math.floorMod(Math.abs(r.nextLong()), upperBound))
    }
    result.toSeq
  }

  override def performTask(): Long = config.distributedDataType match {
    case _ =>
      val keysPerPartition = config.numTotalKeys / config.numPartitions
      val ckeysPerPkey = config.totalOps / config.numTotalKeys
      val sparkPartitionIndex = random.nextInt(config.numPartitions)
      val partitionKeyStart = keysPerPartition * sparkPartitionIndex

      val partitionKey = partitionKeyStart + Math.floorMod(Math.abs(random.nextLong()), keysPerPartition)
      val clusteringKeys =
        if (inKeysCount > ckeysPerPkey)
          for (i <- 0L until ckeysPerPkey) yield i
        else
          uniqueKeys(random, ckeysPerPkey, inKeysCount)
      val ck = clusteringKeys.mkString(",")
      ss.sql(s"select * from $keyspace.$table where key = $partitionKey and col1 in ($ck)").count
  }
}

@ReadTest
class ShortInSelect(config: Config, ss: SparkSession)
  extends AbstractInClauseSelect(config, ss, inKeysCount = 3)

@ReadTest
class WideInSelect(config: Config, ss: SparkSession)
  extends AbstractInClauseSelect(config, ss, inKeysCount = 15)