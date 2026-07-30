// Copy to ecosystem.config.js and fill in real values.
// ecosystem.config.js is gitignored — never commit it.
//
// Generate a token with: openssl rand -hex 32
// Pick an unguessable ntfy topic:  echo "kindred-$(openssl rand -hex 8)"

module.exports = {
  apps: [{
    name: 'kindred',
    script: './server.js',
    cwd: __dirname,
    env: {
      // Shared bearer token. Must match SHARED_TOKEN in the Android
      // app's local.properties. Required — the server exits without it.
      KINDRED_TOKEN: 'REPLACE_WITH_A_RANDOM_64_CHAR_HEX_STRING',

      // ntfy.sh topic for device-offline alerts. Optional; alerts are
      // logged to stdout regardless. Anyone who knows the topic can read
      // your alerts, so keep it unguessable.
      NTFY_TOPIC: 'REPLACE_WITH_YOUR_NTFY_TOPIC'
    }
  }]
}
