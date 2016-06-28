/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kududb.spark.local

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.NullWritable
import org.apache.spark.rdd.RDD
import org.kududb.client._
import org.kududb.mapreduce.KuduTableInputFormat
import scala.collection.mutable
import scala.reflect.ClassTag
import org.apache.spark.{Logging, SparkContext}
import org.apache.spark.streaming.dstream.DStream
import java.io._

/**
  * HBaseContext is a façade for HBase operations
  * like bulk put, get, increment, delete, and scan
  *
  * HBaseContext will take the responsibilities
  * of disseminating the configuration information
  * to the working and managing the life cycle of HConnections.
 */
class KuduContext(@transient sc: SparkContext,
                   @transient kuduMaster: String)
  extends Serializable with Logging {

  val broadcastedKuduMaster = sc.broadcast(kuduMaster)

  LatestKuduContextCache.latest = this

  /**
   * A simple enrichment of the traditional Spark RDD foreachPartition.
   * This function differs from the original in that it offers the
   * developer access to a already connected HConnection object
   *
   * Note: Do not close the HConnection object.  All HConnection
   * management is handled outside this method
   *
   * @param rdd  Original RDD with data to iterate over
   * @param f    Function to be given a iterator to iterate through
   *             the RDD values and a HConnection object to interact
   *             with HBase
   */
  def foreachPartition[T](rdd: RDD[T],
                          f: (Iterator[T], KuduClient, AsyncKuduClient) => Unit):Unit = {
    rdd.foreachPartition(
      it => kuduForeachPartition(it, f))
  }

  /**
   * A simple enrichment of the traditional Spark Streaming dStream foreach
   * This function differs from the original in that it offers the
   * developer access to a already connected HConnection object
   *
   * Note: Do not close the HConnection object.  All HConnection
   * management is handled outside this method
   *
   * @param dstream  Original DStream with data to iterate over
   * @param f        Function to be given a iterator to iterate through
   *                 the DStream values and a HConnection object to
   *                 interact with HBase
   */
  def foreachPartition[T](dstream: DStream[T],
                    f: (Iterator[T], KuduClient, AsyncKuduClient) => Unit):Unit = {
    dstream.foreachRDD((rdd, time) => {
      foreachPartition(rdd, f)
    })
  }

  /**
   * A simple enrichment of the traditional Spark RDD mapPartition.
   * This function differs from the original in that it offers the
   * developer access to a already connected HConnection object
   *
   * Note: Do not close the HConnection object.  All HConnection
   * management is handled outside this method
   *
   * @param rdd  Original RDD with data to iterate over
   * @param mp   Function to be given a iterator to iterate through
   *             the RDD values and a HConnection object to interact
   *             with HBase
   * @return     Returns a new RDD generated by the user definition
   *             function just like normal mapPartition
   */
  def mapPartitions[T, R: ClassTag](rdd: RDD[T],
                                   mp: (Iterator[T], KuduClient, AsyncKuduClient) => Iterator[R]): RDD[R] = {

    rdd.mapPartitions[R](it => kuduMapPartition[T, R](it, mp))

  }

  /**
   * A simple enrichment of the traditional Spark Streaming DStream
   * foreachPartition.
   *
   * This function differs from the original in that it offers the
   * developer access to a already connected HConnection object
   *
   * Note: Do not close the HConnection object.  All HConnection
   * management is handled outside this method
   *
   * Note: Make sure to partition correctly to avoid memory issue when
   *       getting data from HBase
   *
   * @param dstream  Original DStream with data to iterate over
   * @param f       Function to be given a iterator to iterate through
   *                 the DStream values and a HConnection object to
   *                 interact with HBase
   * @return         Returns a new DStream generated by the user
   *                 definition function just like normal mapPartition
   */
  def streamForeachPartition[T](dstream: DStream[T],
                                f: (Iterator[T], KuduClient, AsyncKuduClient) => Unit): Unit = {

    dstream.foreachRDD(rdd => this.foreachPartition(rdd, f))
  }

  /**
   * A simple enrichment of the traditional Spark Streaming DStream
   * mapPartition.
   *
   * This function differs from the original in that it offers the
   * developer access to a already connected HConnection object
   *
   * Note: Do not close the HConnection object.  All HConnection
   * management is handled outside this method
   *
   * Note: Make sure to partition correctly to avoid memory issue when
   *       getting data from HBase
   *
   * @param dstream  Original DStream with data to iterate over
   * @param f       Function to be given a iterator to iterate through
   *                 the DStream values and a HConnection object to
   *                 interact with HBase
   * @return         Returns a new DStream generated by the user
   *                 definition function just like normal mapPartition
   */
  def streamMapPartitions[T, U: ClassTag](dstream: DStream[T],
                                f: (Iterator[T], KuduClient, AsyncKuduClient) => Iterator[U]):
  DStream[U] = {
    dstream.mapPartitions(it => kuduMapPartition[T, U](
      it,
      f))
  }




  def kuduRDD(tableName: String, columnProjection: String = null):
  RDD[(NullWritable, RowResult)] = {

    val conf = new Configuration
    conf.set("kudu.mapreduce.master.address",kuduMaster)
    conf.set("kudu.mapreduce.input.table", tableName)
    if (columnProjection != null) {
      conf.set("kudu.mapreduce.column.projection", columnProjection)
    }

    val rdd = sc.newAPIHadoopRDD(conf, classOf[KuduTableInputFormat], classOf[NullWritable], classOf[RowResult])

    rdd
  }


  /**
   *  underlining wrapper all foreach functions in HBaseContext
   */
  private def kuduForeachPartition[T](it: Iterator[T],
                                        f: (Iterator[T], KuduClient, AsyncKuduClient) => Unit) = {
    f(it, KuduClientCache.getKuduClient(broadcastedKuduMaster.value),
    KuduClientCache.getAsyncKuduClient(broadcastedKuduMaster.value))
  }

  /**
   *  underlining wrapper all mapPartition functions in HBaseContext
   *
   */
  private def kuduMapPartition[K, U](it: Iterator[K],
                                     mp: (Iterator[K], KuduClient, AsyncKuduClient) =>
                                         Iterator[U]): Iterator[U] = {

    
    val res = mp(it,
      KuduClientCache.getKuduClient(broadcastedKuduMaster.value),
      KuduClientCache.getAsyncKuduClient(broadcastedKuduMaster.value))
    
    res

  }

  /**
   *  underlining wrapper all get mapPartition functions in HBaseContext
   */
  private class ScannerMapPartition[T, U](batchSize: Integer,
                                      makeScanner: (T, KuduClient, AsyncKuduClient) => KuduScanner,
                                      convertResult: (RowResultIterator) => U)
    extends Serializable {

    def run(iterator: Iterator[T], kuduClient: KuduClient, asyncKuduClient: AsyncKuduClient): Iterator[U] = {


      iterator.flatMap( t => {
        val resultList = new mutable.MutableList[U]
        val scanner = makeScanner(t, kuduClient, asyncKuduClient)

        while (scanner.hasMoreRows) {
          resultList.+=(convertResult(scanner.nextRows()))
        }
        resultList.iterator
      })
    }
  }

  /**
   * Produces a ClassTag[T], which is actually just a casted ClassTag[AnyRef].
   *
   * This method is used to keep ClassTags out of the external Java API, as
   * the Java compiler cannot produce them automatically. While this
   * ClassTag-faking does please the compiler, it can cause problems at runtime
   * if the Scala API relies on ClassTags for correctness.
   *
   * Often, though, a ClassTag[AnyRef] will not lead to incorrect behavior,
   * just worse performance or security issues.
   * For instance, an Array of AnyRef can hold any type T, but may lose primitive
   * specialization.
   */
  private[spark]
  def fakeClassTag[T]: ClassTag[T] = ClassTag.AnyRef.asInstanceOf[ClassTag[T]]
}

object LatestKuduContextCache {
  var latest:KuduContext = null
}

object KuduClientCache {
  var kuduClient: KuduClient = null
  var asyncKuduClient: AsyncKuduClient = null

  def getKuduClient(kuduMaster: String): KuduClient = {
    this.synchronized {
      if (kuduClient == null) {
        kuduClient = new KuduClient.KuduClientBuilder(kuduMaster).build()
      }
    }
    kuduClient
  }

  def getAsyncKuduClient(kuduMaster: String): AsyncKuduClient = {
    this.synchronized {
      if (asyncKuduClient == null) {
        asyncKuduClient = new AsyncKuduClient.AsyncKuduClientBuilder(kuduMaster).build()
      }
    }
    asyncKuduClient
  }

}