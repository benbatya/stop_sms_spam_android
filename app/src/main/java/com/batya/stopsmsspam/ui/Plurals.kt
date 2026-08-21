package com.batya.stopsmsspam.ui

/** Counted nouns, so a one-item batch does not read as "1 replies". */
internal fun countOf(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count " + if (count == 1) singular else plural
