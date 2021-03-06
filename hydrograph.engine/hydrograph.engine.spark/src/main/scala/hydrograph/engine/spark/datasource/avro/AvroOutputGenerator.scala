/*******************************************************************************
 * Copyright 2017 Capital One Services, LLC and Bitwise, Inc.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package hydrograph.engine.spark.datasource.avro

import java.io.{IOException, OutputStream}
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.util.Date

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData.Record
import org.apache.avro.generic.GenericRecord
import org.apache.avro.mapred.AvroKey
import org.apache.avro.mapreduce.AvroKeyOutputFormat
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.NullWritable
import org.apache.hadoop.mapreduce.{RecordWriter, TaskAttemptContext, TaskAttemptID}
import org.apache.spark.sql.Row
import org.apache.spark.sql.execution.datasources.OutputWriter
import org.apache.spark.sql.types._
/**
  * The Class AvroOutputGenerator.
  *
  * @author Bitwise
  *
  */
private class AvroOutputGenerator(
    path: String,
    context: TaskAttemptContext,
    schema: StructType,
    recordName: String,
    recordNamespace: String) extends OutputWriter {

  var schemas: Schema = null;
  private lazy val converter = createConverterToAvro(schemas, schema, recordName, recordNamespace)

  private val recordWriter: RecordWriter[AvroKey[GenericRecord], NullWritable] =
    new AvroKeyOutputFormat[GenericRecord]() {

      override def getDefaultWorkFile(context: TaskAttemptContext, extension: String): Path = {
        val uniqueWriteJobId = context.getConfiguration.get("spark.sql.sources.writeJobUUID")
        val taskAttemptId: TaskAttemptID = context.getTaskAttemptID
        val split = taskAttemptId.getTaskID.getId
        new Path(path, f"part-r-$split%05d-$uniqueWriteJobId$extension")
      }

      @throws(classOf[IOException])
      override def getAvroFileOutputStream(c: TaskAttemptContext): OutputStream = {
        val path = getDefaultWorkFile(context, ".avro")
        path.getFileSystem(context.getConfiguration).create(path)
      }

    }.getRecordWriter(context)

  override def write(row: Row): Unit = {
    val key = new AvroKey(converter(row).asInstanceOf[GenericRecord])
    recordWriter.write(key, NullWritable.get())
  }

  override def close(): Unit = recordWriter.close(context)

  private def toAvroDecimal(schema: Schema, item: Any): AnyRef = {
    decimalToBinary(item.asInstanceOf[BigDecimal], schema);
  }

  private def decimalToBinary(bigDecimal: BigDecimal, schema: Schema): AnyRef = {
    val prec = bigDecimal.precision()
    val scale = bigDecimal.scale()
    val decimalBytes = bigDecimal.setScale(scale).unscaledValue().toByteArray()
    val precToBytes = PRECISION_TO_BYTE_COUNT(prec - 1)
    if (precToBytes == decimalBytes.length) {
      return ByteBuffer.wrap(decimalBytes)
    }
    val tgt = Array.ofDim[Byte](precToBytes)
    if (bigDecimal.signum() == -1) {
      for (i <- 0 until precToBytes) {
        tgt(i) != 0xFF //needs to be changed       
      }
    }
    System.arraycopy(decimalBytes, 0, tgt, precToBytes - decimalBytes.length, decimalBytes.length)
    ByteBuffer.wrap(tgt)
  }
  val PRECISION_TO_BYTE_COUNT: Array[Int] = new Array[Int](38)
  var prec = 1
  while (prec <= 38) {
    PRECISION_TO_BYTE_COUNT(prec - 1) = Math.ceil((Math.log(Math.pow(10, prec) - 1) / Math.log(2) + 1) /
      8).toInt
    prec += 1
  }

  private def createConverterToAvro(schema: Schema,
    dataType: DataType,
    structName: String,
    recordNamespace: String): (Any) => Any = {
    dataType match {
      case BinaryType => (item: Any) => item match {
        case null => null
        case bytes: Array[Byte] => ByteBuffer.wrap(bytes)
      }
      case ByteType | IntegerType | LongType |
        FloatType | DoubleType | StringType | BooleanType => identity
      case ShortType => (item: Any) => if (item == null) null else
        item.asInstanceOf[Short]
      case _: DecimalType => (item: Any) =>
        toAvroDecimal(schema, item.asInstanceOf[BigDecimal])
        case TimestampType => (item: Any) =>
        if (item == null) null else
          item.asInstanceOf[Date].getTime
        case DateType => (item: Any) => if (item == null) null else
        item.asInstanceOf[Date].getTime()
      case ArrayType(elementType, _) =>
        val elementConverter = createConverterToAvro(schema, elementType, structName, recordNamespace)
        (item: Any) => {
          if (item == null) {
            null
          } else {
            val sourceArray = item.asInstanceOf[Seq[Any]]
            val sourceArraySize = sourceArray.size
            val targetArray = new Array[Any](sourceArraySize)
            var idx = 0
            while (idx < sourceArraySize) {
              targetArray(idx) = elementConverter(sourceArray(idx))
              idx += 1
            }
            targetArray
          }
        }
        case structType: StructType =>
        val precision = CustomSparkToAvro.getPrecison()
        val scale = CustomSparkToAvro.getScale()
        var schema: Schema = CustomSparkToAvro.generateAvroSchemaFromFieldsAndTypes(recordNamespace, structType, precision, scale)
        val fieldConverters = structType.fields.map(field =>
          createConverterToAvro(schema, field.dataType, field.name, recordNamespace))
        (item: Any) => {
          if (item == null) {
            null
          } else {
            val record = new Record(schema)
            val convertersIterator = fieldConverters.iterator
            val fieldNamesIterator = dataType.asInstanceOf[StructType].fieldNames.iterator
            val rowIterator = item.asInstanceOf[Row].toSeq.iterator
            while (convertersIterator.hasNext) {
              val converter = convertersIterator.next()
              record.put(fieldNamesIterator.next(), converter(rowIterator.next()))
            }
            record
          }
        }
    }
  }
}
