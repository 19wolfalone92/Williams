package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsCourseQuantityTest {
    private fun contract()=JSONObject(javaClass.classLoader!!.getResourceAsStream("williams_course_quantity_contract.json")!!.bufferedReader().use { it.readText() })
    @Test fun sharedBookProportionsNeverRoundOrClipAnEstablishedTranche() {
        val cases=contract().getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i)
            val result=if(c.optString("kind")=="INITIAL") williamsCourseInitialQuantity(c.opt("cap"),c.opt("lot"),c.opt("market") ?: "0",c.optString("stage","W1"))
                else williamsCourseStageQuantity(c.opt("unit") ?: "0.001",c.getString("from"),c.getString("stage"),c.opt("owned"),
                    c.opt("lot") ?: "0.001",c.opt("market") ?: "0",c.opt("cap"),if(c.has("ratio")) c.getDouble("ratio") else null)
            val id=c.getString("id")
            assertFalse(id,result.getBoolean("can_place_orders"))
            if(c.has("reason")) { assertEquals(id,"BLOCKED",result.getString("status"));assertEquals(id,c.getString("reason"),result.getString("reason")) }
            else {
                assertEquals(id,"EXACT_COURSE_QUANTITY",result.getString("status"));assertEquals(id,c.getString("quantity"),result.getString("quantity"))
                if(c.has("unit")) assertEquals(id,c.getString("unit"),result.getString("unit_quantity"))
                if(c.has("target")) assertEquals(id,c.getString("target"),result.getString("target_quantity"))
                if(c.has("action")) assertEquals(id,c.getString("action"),result.getString("action"))
            }
        }
    }
    private fun fill(proposal:JSONObject,direction:String):Pair<JSONObject,JSONObject> {
        val side=if(direction=="LONG") "BUY" else "SELL";val reduce=proposal.getString("action")=="REDUCE"
        val order=JSONObject().put("symbol","BTCUSDT").put("clientOrderId","actual-child").put("orderId","42").put("status","FILLED")
            .put("side",if(reduce) (if(side=="BUY") "SELL" else "BUY") else side).put("positionSide","BOTH").put("reduceOnly",reduce)
            .put("origQty",proposal.getString("quantity")).put("executedQty",proposal.getString("quantity"))
        val qty=proposal.getString("target_quantity").toBigDecimal()*(if(direction=="LONG") java.math.BigDecimal.ONE else -java.math.BigDecimal.ONE)
        return order to JSONObject().put("symbol","BTCUSDT").put("positionSide","BOTH").put("positionAmt",qty.toPlainString())
    }
    @Test fun stageConfirmationRequiresItsExactSignedOrderAndProtectedPosition() {
        for(direction in listOf("LONG","SHORT")) for(kind in listOf("ENTRY","ADD","REDUCE","FLAT")) for(fault in listOf(
            "NONE","PARTIAL","CANCELED_PARTIAL","WRONG_POSITION","WRONG_CHILD","WRONG_SYMBOL","WRONG_SIDE","WRONG_REDUCE",
            "WRONG_ORIGINAL_QTY","UNPROTECTED","TAMPERED_STAGE","TAMPERED_ACTION")) {
            val p=when(kind) {
                "ENTRY"->williamsCourseInitialQuantity("0.009","0.001")
                "ADD"->williamsCourseStageQuantity("0.001","W2","W3","0.001","0.001",riskCap="0.005")
                "REDUCE"->williamsCourseStageQuantity("0.001","W1","W2","0.003","0.001")
                else->williamsCourseStageQuantity("0.001","C_ADD","CORRECTION_EXIT","0.004","0.001")
            }
            val (order,position)=fill(p,direction)
            when(fault) {
                "PARTIAL","CANCELED_PARTIAL"->order.put("status",if(fault=="PARTIAL") "PARTIALLY_FILLED" else "CANCELED")
                    .put("executedQty",order.getString("executedQty").toBigDecimal().divide(java.math.BigDecimal(2)).toPlainString())
                "WRONG_POSITION"->position.put("positionAmt","0.000001")
                "WRONG_CHILD"->order.put("orderId","99")
                "WRONG_SYMBOL"->order.put("symbol","ETHUSDT")
                "WRONG_SIDE"->order.put("side",if(order.getString("side")=="BUY") "SELL" else "BUY")
                "WRONG_REDUCE"->order.put("reduceOnly",!order.getBoolean("reduceOnly"))
                "WRONG_ORIGINAL_QTY"->order.put("origQty","0.099")
                "TAMPERED_STAGE"->p.put("stage","W3_EXTENSION")
                "TAMPERED_ACTION"->p.put("action","HOLD")
            }
            val blocker=williamsCourseFillBlocker(p,order,position,"BTCUSDT",direction,"actual-child","42",fault!="UNPROTECTED")
            assertEquals("$direction $kind $fault $blocker",fault=="NONE" || fault=="UNPROTECTED" && kind=="FLAT",blocker==null)
        }
    }
    @Test fun wholeMotiveAndCorrectiveSequencesPreserveOneActualUnit() {
        val cases=listOf(listOf("W1","W2","W3","W3_EXTENSION","W4","W5","EXIT") to 2.0,
            listOf("W1","W2","W3","W4","W5","EXIT") to 1.5,listOf("A_ENTRY","A_REDUCE","C_ADD","CORRECTION_EXIT") to null)
        for((stages,ratio) in cases) {
            val initial=williamsCourseInitialQuantity("1.007","0.002","0.003",stages.first())
            val unit=initial.getString("unit_quantity");var owned=initial.getString("target_quantity")
            for((from,stage) in stages.zipWithNext()) {
                val p=williamsCourseStageQuantity(unit,from,stage,owned,"0.002","0.003","100",ratio)
                assertEquals(p.toString(),"EXACT_COURSE_QUANTITY",p.getString("status"));assertEquals(unit,p.getString("unit_quantity"))
                for(direction in listOf("LONG","SHORT")) {
                    val (order,position)=fill(p,direction)
                    assertNull(williamsCourseFillBlocker(p,order,position,"BTCUSDT",direction,"actual-child","42",true))
                }
                owned=p.getString("target_quantity")
            }
            assertEquals("0",owned)
        }
    }
    @Test fun subLotNoiseAndPartialInitialUnitsCannotBecomeStageAuthority() {
        assertNull(williamsCourseOwnedQuantityBlocker("0.001","W1","0.003","0.001"))
        assertEquals("COURSE_OWNED_STAGE_QUANTITY_CHANGED",williamsCourseOwnedQuantityBlocker("0.001","W1","0.0030000000001","0.001"))
        assertEquals("COURSE_UNIT_NOT_ON_EXCHANGE_GRID",williamsCourseOwnedQuantityBlocker("0.0015","W1","0.0045","0.001"))
    }
}
