import { NitroModules } from 'react-native-nitro-modules'
import type { NitroGeolocation as NitroGeolocationSpec } from './specs/nitro-geolocation.nitro'

export const NitroGeolocation =
  NitroModules.createHybridObject<NitroGeolocationSpec>('NitroGeolocation')