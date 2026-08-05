# Dominion Sword: MTR Compatibility

An MIT-licensed Forge 1.20.1 add-on connecting Dominion Sword to Minecraft Transit Railway 4.0.5.

## Features

- Invisible server-side train proxies for Dominion Sword selection and control.
- Multi-car boarding for controlled mobs through real MTR doorway geometry.
- Destination selection limited to later stations on the train's current service.
- Sampled MTR rail paths for Dominion Sword route visualization.
- Emergency braking, hold-at-destination behavior, and automatic departure after choosing a station.
- Persistent non-player occupants across MTR train recreation and server restarts.
- Optional per-car physical collision, real door openings, roof carrying, debug boxes, safe dismounts, and interior-stowaway ejection.
- Real-player driving always takes priority. Empty trains return to native MTR control.
- MTR trains are exclusive selections and cannot be stored in Dominion Sword squads.

The add-on does not copy or redistribute MTR models, textures, or other assets.

## Requirements

- Minecraft 1.20.1
- Forge 47.x
- Dominion Sword 1.19.0 or newer
- Minecraft Transit Railway 4.0.5
- Java 17

## Building

Place the two dependency jars anywhere outside version control, then run:

```powershell
.\gradlew.bat build `
  -Pdominionsword_jar="C:\path\to\dominionsword.jar" `
  -Pmtr_jar="C:\path\to\MTR-forge-4.0.5+1.20.1.jar"
```

The dependencies are compile-only and are not bundled into the output jar.

For an optional local auto-deploy, create an ignored `local.properties` file:

```properties
deploy_directory=C:/path/to/.minecraft/versions/your-instance/mods
deploy_prefix=[Dominion Sword - MTR Compatibility]
```

## License

This add-on's source code and resources are available under the [MIT License](LICENSE). Minecraft Transit Railway and Dominion Sword remain subject to their own licenses.
