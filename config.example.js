// NexGram Configuration Template
// Rename this file to 'config.js' or set environment variables

const os = require('os');

module.exports = {
  // Get your API_ID and API_HASH from https://my.telegram.org
  API_ID: process.env.API_ID ? parseInt(process.env.API_ID) : 0,
  API_HASH: process.env.API_HASH || 'YOUR_TELEGRAM_API_HASH',
  
  PORT: process.env.PORT || 3000,
  APP_NAME: 'NexGram',
  VERSION: '13.2.2',
  DEVICE_MODEL: process.env.DEVICE_MODEL || '',
  SYSTEM_VERSION: process.env.SYSTEM_VERSION || `${os.type()} ${os.release()}`
};
