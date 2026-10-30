package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.Writer

/**
 * Bounded-stack JSON serializer for already-captured Android diagnostics.
 * Unlike JSONObject.toString(2), no contiguous multi-megabyte report String is
 * constructed before ZIP compression. Only one scalar string is streamed at
 * a time and every JSON field remains available in the forensic archive.
 */
internal fun writeDiagnosticsJsonStreaming(value:Any?, writer:Writer, depth:Int=0) {
    if(depth>100) throw IOException("Diagnostic JSON is nested too deeply")
    when(value) {
        null, JSONObject.NULL -> writer.write("null")
        is JSONObject -> {
            writer.write("{")
            val keys=value.keys()
            var first=true
            while(keys.hasNext()) {
                val key=keys.next()
                if(!first) writer.write(",")
                first=false
                writeJsonEscapedString(key,writer)
                writer.write(":")
                writeDiagnosticsJsonStreaming(value.opt(key),writer,depth+1)
            }
            writer.write("}")
        }
        is JSONArray -> {
            writer.write("[")
            for(index in 0 until value.length()) {
                if(index>0) writer.write(",")
                writeDiagnosticsJsonStreaming(value.opt(index),writer,depth+1)
            }
            writer.write("]")
        }
        is String -> writeJsonEscapedString(value,writer)
        is Char -> writeJsonEscapedString(value.toString(),writer)
        is Boolean -> writer.write(if(value) "true" else "false")
        is Number -> {
            if(value.toDouble().isNaN() || value.toDouble().isInfinite())
                throw IOException("Non-finite diagnostic JSON number")
            writer.write(value.toString())
        }
        else -> throw IOException("Unsupported diagnostic JSON value: "+value.javaClass.simpleName)
    }
}

/** Writes long values in chunks; allocating a single JSON-escaped copy of the
 * complete text is prohibited on phones close to the Java heap limit.
 */
private fun writeJsonEscapedString(value:String,writer:Writer) {
    writer.write("\"")
    var from=0
    for(i in value.indices) {
        val ch=value[i]
        val escaped:String?=when(ch) {
            '"' -> "\\\""
            '\\' -> "\\\\"
            '\b' -> "\\b"
            '\u000c' -> "\\f"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> if(ch<' ' || ch=='\u2028' || ch=='\u2029')
                "\\u"+ch.code.toString(16).padStart(4,'0') else null
        }
        if(escaped!=null) {
            if(i>from) writer.write(value,from,i-from)
            writer.write(escaped)
            from=i+1
        } else if(i-from>=8192) {
            writer.write(value,from,i-from+1)
            from=i+1
        }
    }
    if(from<value.length) writer.write(value,from,value.length-from)
    writer.write("\"")
}

/** UI JSON excerpt must never allocate the entire multi-megabyte diagnostic
 * by calling JSONObject.toString(2) just to display its first 20,000 chars.
 */
internal fun diagnosticsJsonPreview(value:Any?,limit:Int=20_000):String {
    require(limit in 1..100_000)
    val out=StringBuilder(minOf(limit,1024))
    class PreviewComplete:java.io.IOException()
    val writer=object:Writer() {
        private fun capture(value:String,off:Int,length:Int) {
            if(length<=0) return
            val take=minOf(length,(limit-out.length).coerceAtLeast(0))
            if(take>0) out.append(value,off,off+take)
            if(take<length) throw PreviewComplete()
        }
        override fun write(value:String,off:Int,length:Int) = capture(value,off,length)
        override fun write(buffer:CharArray,off:Int,length:Int) {
            if(length<=0) return
            val take=minOf(length,(limit-out.length).coerceAtLeast(0))
            if(take>0) out.append(buffer,off,take)
            if(take<length) throw PreviewComplete()
        }
        override fun flush() {}
        override fun close() {}
    }
    try {
        writeDiagnosticsJsonStreaming(value,writer)
    } catch (_:PreviewComplete) {
        out.append("\n… [Фрагмент ограничен. Полный JSON — в ZIP]")
    }
    return out.toString()
}
