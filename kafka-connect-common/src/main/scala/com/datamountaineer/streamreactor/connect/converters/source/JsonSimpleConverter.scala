/*
 *
 *  * Copyright 2020 Lenses.io.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.datamountaineer.streamreactor.connect.converters.source

import com.datamountaineer.streamreactor.common.converters.MsgKey
import com.typesafe.scalalogging.StrictLogging

import java.nio.charset.Charset
import java.util
import java.util.Collections
import org.apache.kafka.connect.data._
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.source.SourceRecord
import org.json4s
import org.json4s.native.JsonParser

import collection.JavaConverters._
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}
import org.json.JSONObject

class JsonSimpleConverter extends Converter {
  override def convert(
      kafkaTopic: String,
      sourceTopic: String,
      messageId: String,
      bytes: Array[Byte],
      keys: Seq[String] = Seq.empty,
      keyDelimiter: String = ".",
      properties: Map[String, String] = Map.empty): SourceRecord = {
    if (bytes == null)
      throw new ConnectException("Invalid input. Input cannot be null.")
    val json = new String(bytes, Charset.defaultCharset)
    val schemaAndValue = JsonSimpleConverter.convert(sourceTopic, json)
    val value = schemaAndValue.value()
    value match {
      case s: Struct if keys.nonEmpty =>
        val keysValue = keys
          .flatMap { key =>
            Option(KeyExtractor.extract(s, key.split('.').toVector))
              .map(_.toString)
          }
          .mkString(keyDelimiter)

        new SourceRecord(
          Collections.singletonMap(Converter.TopicKey, sourceTopic),
          null,
          kafkaTopic,
          Schema.STRING_SCHEMA,
          keysValue,
          schemaAndValue.schema(),
          schemaAndValue.value()
        )
      case _ =>
        new SourceRecord(
          Collections.singletonMap(Converter.TopicKey, sourceTopic),
          null,
          kafkaTopic,
          MsgKey.schema,
          MsgKey.getStruct(sourceTopic, messageId),
          schemaAndValue.schema(),
          schemaAndValue.value()
        )
    }

  }
}

object JsonSimpleConverter extends StrictLogging {

  import org.json4s._

  def convert(name: String, str: String): SchemaAndValue = {
    val json = Try(JsonParser.parse(str)) match {
      case Success(s) => s
      case Failure(f) =>
        throw new ConnectException(s"Failed to parse json message [$str]", f)
    }

    json match {
      case JNothing =>
        logger.error(s"Possible failure to parse json message, potential invalid json [$str]")
      case _ =>
    }

    convert(name, json)
  }

  def convert(name: String, value: JValue): SchemaAndValue = {
    value match {
      case JArray(arr) => handleArray(name, arr)
      case JBool(b)    => new SchemaAndValue(Schema.OPTIONAL_BOOLEAN_SCHEMA, b)
      case JDecimal(d) =>
        val schema = Decimal.builder(d.scale).optional().build()
        new SchemaAndValue(schema, Decimal.fromLogical(schema, d.bigDecimal))
      case JDouble(d) => new SchemaAndValue(Schema.OPTIONAL_FLOAT64_SCHEMA, d)
      case JInt(i) =>
        new SchemaAndValue(Schema.OPTIONAL_INT64_SCHEMA, i.toLong) //on purpose! LONG (we might get later records with long entries)
      case JLong(l) => new SchemaAndValue(Schema.OPTIONAL_INT64_SCHEMA, l)
      case JNull | JNothing =>
        new SchemaAndValue(Schema.OPTIONAL_STRING_SCHEMA, null)
      case JString(s)      => new SchemaAndValue(Schema.OPTIONAL_STRING_SCHEMA, s)
      case JObject(values) => handleObject(name, values)
    }
  }
  private def handleArray(name: String,
                          arr: List[_root_.org.json4s.JsonAST.JValue]): SchemaAndValue = {
    if (arr.nonEmpty) {
      val (schema, values) = arr.head match {
        case JObject(obj) => {
            val arr_as_objects = arr.flatMap(x => if (x.isInstanceOf[JObject]) Seq(x.asInstanceOf[JObject]) else Seq())
            val all_fields = arr_as_objects.flatMap(_.obj)
            val unique_fields = all_fields.foldLeft(Nil: List[JField]) {(acc,next) => { if (acc.map(_._1).contains(next._1)) acc else next :: acc}}
            val sv = handleObject(name, unique_fields)
            val values = arr_as_objects.map(x => {
              convert(name, JObject(unique_fields.map(_._1).map(name => {
                  x.obj.find(_._1 == name).orElse(Some(JField(name, JNull))).get
              }))).value();

            });
            
            (sv.schema(), values.asJava)
        }
        case default => {
          val values = new util.ArrayList[AnyRef]()
          val sv = convert(name, arr.head)
          val vals = arr.foreach { v =>
            values.add(convert(name, v).value())
          }
          (sv.schema(), values)
        }
      };

      val s = SchemaBuilder.array(schema).optional().build()
      val sc = SchemaString(schema);
      new SchemaAndValue(s, values);
    } else {
      val s = SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build()
      new SchemaAndValue(s, new util.ArrayList[AnyRef]())
    }

  }

  def SchemaString(schem: Schema): String = {
    val t: Schema.Type = schem.`type`();
    val ss = t.toString;
    val x = if (schem.name == null)  "" else schem.name;
    var s = s"[$x $ss"
    if (t == Schema.Type.ARRAY) {
      s += " " + SchemaString(schem.valueSchema())
    } else if (t == Schema.Type.MAP) {
      s += " " + SchemaString(schem.valueSchema())
    } else if (t == Schema.Type.STRUCT) {
      schem.fields().forEach( f => {
        s += "{" + f.name + "=" + SchemaString(f.schema()) + "}"
      });
    }
    s += "]"
    s
    
  }

  private def handleObject(name: String,
                           values: List[(String, json4s.JValue)]) = {
    val builder = SchemaBuilder.struct().name(name.replace("/", "_"))
    val fields = values.map {
      case (n, v) =>
        val schemaAndValue = convert(n, v)
        builder.field(n, schemaAndValue.schema())
        n -> schemaAndValue.value()
    }.toMap
    val schema = builder.build()

    val struct = new Struct(schema)
    fields.foreach { case (field, v) =>  {
      val s = SchemaString(schema);
      logger.warn(s"PUTPUT PUTPUT: $name=$s SET $field TO $v");
      struct.put(field, v)
     } }

    new SchemaAndValue(schema, struct)
  }
}
