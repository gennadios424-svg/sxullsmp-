# SXULL Coinflip

Paper plugin for player-versus-player coinflip challenges. All stake and payout transactions use the server's registered Vault economy provider; this plugin does not create a separate balance system.

## Build
Requires the Java version specified in `pom.xml` and Maven:
```sh
mvn clean verify
```
The output JAR is `target/SXULL-Coinflip-1.0.0.jar`. GitHub Actions publishes it as a build artifact after a successful build.

## Install
1. Back up the server.
2. Install and enable Vault and a compatible economy plugin.
3. Put the compiled JAR in `plugins/`.
4. Restart the server and confirm the console reports a successful Vault economy hook.
5. Run `/coinflip`; test with two players and small stakes first.

Do not deploy until the GitHub Actions build succeeds and the plugin has been integration-tested on the exact Paper server build.