/**
 * Configuration module for CUBRID Test Report Server
 */

const path = require('path');
const os = require('os');

// Load environment variables
require('dotenv').config();

// Get local IP address
function getLocalIpAddress() {
    const interfaces = os.networkInterfaces();
    for (const name of Object.keys(interfaces)) {
        for (const iface of interfaces[name]) {
            if (!iface.internal && iface.family === 'IPv4') {
                return iface.address;
            }
        }
    }
    return '127.0.0.1';
}

const config = {
    // Server configuration
    server: {
        port: process.env.REPORT_PORT || process.argv[2] || 8091,
        host: process.env.HOST || '0.0.0.0',
        localIp: getLocalIpAddress(),
        hostname: os.hostname()
    },

    // Builder configuration
    builder: {
        host: process.env.BUILDER_HOST || 'localhost',
        port: process.env.BUILDER_PORT || 8089,
        protocol: process.env.BUILDER_PROTOCOL || 'http'
    },

    // Paths configuration
    paths: {
        logBase: path.join(
            process.env.HOME || process.env.USERPROFILE,
            'cubrid-testtools', 'CTP', 'builder_tester', 'log'
        ),
        results: path.join(
            process.env.HOME || process.env.USERPROFILE,
            'cubrid-testtools', 'CTP', 'builder_tester', 'log', 'results'
        ),
        requests: path.join(
            process.env.HOME || process.env.USERPROFILE,
            'cubrid-testtools', 'CTP', 'builder_tester', 'log', 'requests'
        ),
        templateDir: path.join(__dirname, '..', '..', 'views'),
        publicDir: path.join(__dirname, '..', '..', 'public'),
        tobeHtml: path.join(__dirname, '..', '..', 'report-server-tobe.html')
    },

    // GitHub configuration
    github: {
        token: process.env.GITHUB_TOKEN,
        apiUrl: 'https://api.github.com',
        owner: 'CUBRID',
        repo: 'cubrid',
        defaultBranch: 'develop'
    },

    // Application settings
    app: {
        name: 'CUBRID Test Report Server',
        version: '4.0.0',
        environment: process.env.NODE_ENV || 'production'
    },

    // Security settings
    security: {
        enableCors: true,
        corsOrigin: process.env.CORS_ORIGIN || '*',
        rateLimit: {
            windowMs: 15 * 60 * 1000, // 15 minutes
            max: 100 // limit each IP to 100 requests per windowMs
        }
    }
};

module.exports = config;
