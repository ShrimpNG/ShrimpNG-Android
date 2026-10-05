package com.v2ray.ang.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.v2ray.ang.dto.entities.AssetUrlCache
import com.v2ray.ang.handler.GeoAssetUpdater

class UserAssetViewModel : ViewModel() {
    private val assets = mutableListOf<AssetUrlCache>()

    val itemCount: Int
        get() = assets.size

    fun getAssets(): List<AssetUrlCache> = assets.toList()

    fun getAsset(position: Int): AssetUrlCache? = assets.getOrNull(position)

    fun reload(geoFilesSource: String) {
        assets.clear()
        assets.addAll(GeoAssetUpdater.buildAssetList(geoFilesSource))
    }

    fun downloadGeoFiles(context: Context): GeoAssetUpdater.Result =
        GeoAssetUpdater.downloadAll(context, getAssets())
}
