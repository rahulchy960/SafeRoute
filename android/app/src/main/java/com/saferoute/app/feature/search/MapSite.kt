// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.search

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** The public site of the map data. A fixed address: nothing about the user or a search is in it. */
internal const val MAP_SITE_URL = "https://www.openstreetmap.org/"

/**
 * An intent that asks Android to SHOW a web page (`ACTION_VIEW`). The system picks the browser;
 * the app itself sends nothing to the site and needs no permission for this.
 */
internal fun mapSiteIntent(): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(MAP_SITE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Opens the map data's site in the browser. False when the phone has no app that can. */
internal fun openMapSite(context: Context): Boolean =
    try {
        context.startActivity(mapSiteIntent())
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
