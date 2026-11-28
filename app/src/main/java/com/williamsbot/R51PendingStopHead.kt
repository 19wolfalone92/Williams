package com.williamsbot

/** A single pending STOP head per direction; never identifies an actual fill.
 * Equal-level ties are stable identifiers, not a fictional exchange sequence.
 * Cross-direction ordering is not derivable from geometry.
 */
internal fun <T> williamsPendingStopHeads(
    candidates:List<T>, direction:(T)->String, level:(T)->Double, identity:(T)->String
):List<T> = candidates.groupBy(direction).values.mapNotNull { sameSide ->
    val side=direction(sameSide.first())
    if(side !in setOf("LONG","SHORT")) return@mapNotNull null
    sameSide.filter { level(it).isFinite() && level(it)>0.0 && identity(it).isNotBlank() }
        .minWithOrNull(compareBy<T> { if(side=="LONG") level(it) else -level(it) }.thenBy(identity))
}
