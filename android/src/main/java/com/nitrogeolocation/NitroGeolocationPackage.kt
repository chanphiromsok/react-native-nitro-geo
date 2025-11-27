package com.nitrogeolocation

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.margelo.nitro.nitrogeolocation.NitroGeolocationOnLoad


class NitroGeolocationPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
    // Set the static context for HybridNitroGeolocation when the package is loaded
//    HybridNitroGeolocation.appContext = reactContext.applicationContext
    return null
  }

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider = ReactModuleInfoProvider { emptyMap() }

  companion object {
    init {
      NitroGeolocationOnLoad.initializeNative()
    }
  }
}
