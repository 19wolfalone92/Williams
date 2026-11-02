package com.williamsbot

/** TC2 second Wise Man (chapter 10, PDF 137-139): the third ACTUAL
 * consecutive AO increase is a signal, not the third positive AO value.
 * A crossing of AO zero is irrelevant to its colour; flat/NaN breaks a streak.
 * SHORT reuses mirrored market geometry; directional AO is inverted there.
 *
 * Used by Android H1 signal scanner; pure function for mirrored book tests.
 */
internal fun williamsTc2ConsecutiveAoRise(
    lastIndex:Int,
    minSignalIndex:Int=35,
    aoAt:(Int)->Double
):Int {
    if(lastIndex<minSignalIndex || minSignalIndex<1) return 0
    var count=0
    for(i in lastIndex downTo minSignalIndex) {
        val now=aoAt(i)
        val before=aoAt(i-1)
        if(!now.isFinite() || !before.isFinite() || now<=before) break
        count++
    }
    return count
}
