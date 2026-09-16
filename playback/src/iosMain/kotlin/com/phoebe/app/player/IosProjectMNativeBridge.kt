package com.phoebe.app.player

import platform.UIKit.UIView

interface IosProjectMNativeViewFactory {
    fun create(
        presetPath: String?,
        presetData: String?,
        locked: Boolean,
        playing: Boolean,
        suspended: Boolean,
    ): UIView

    fun update(
        view: UIView,
        presetPath: String?,
        presetData: String?,
        locked: Boolean,
        playing: Boolean,
        suspended: Boolean,
    )

    fun addPcm(view: UIView, samples: FloatArray, channels: Int)

    fun setSuspended(view: UIView, suspended: Boolean)

    fun isNativeReady(view: UIView): Boolean
}

object IosProjectMNativeBridge {
    var factory: IosProjectMNativeViewFactory? = null
}
