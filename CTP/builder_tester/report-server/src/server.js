/**
 * CUBRID Test Report Server - Main Application
 */

const express = require('express');
const path = require('path');
const helmet = require('helmet');
const compression = require('compression');
const morgan = require('morgan');
const rateLimit = require('express-rate-limit');

const config = require('./config');
const fileService = require('./services/fileService');
const corsMiddleware = require('./middleware/cors');
const errorHandler = require('./middleware/errorHandler');

// Import routes
const apiRoutes = require('./routes/api');
const dashboardRoutes = require('./routes/dashboard');
const reportRoutes = require('./routes/reports');
const healthRoutes = require('./routes/health');

// Create Express app
const app = express();

// View engine setup
app.set('views', path.join(__dirname, '..', 'views'));
app.set('view engine', 'ejs');

// Security middleware
app.use(helmet({
    contentSecurityPolicy: false // Disable for development
}));

// Compression middleware
app.use(compression());

// Logging middleware
if (config.app.environment !== 'test') {
    app.use(morgan('combined'));
}

// Rate limiting
// Default limiter protects API endpoints, but the dashboard does high-frequency reads (queue/status + log metadata).
// Make those endpoints more lenient to avoid 429s during normal UI usage.
const defaultLimiter = rateLimit(config.security.rateLimit);
const highFreqLimiter = rateLimit({
    ...config.security.rateLimit,
    max: Math.max(config.security.rateLimit.max || 0, 2000)
});

app.use('/api/', (req, res, next) => {
    const p = req.path || '';
    // High-frequency dashboard reads
    if (p === '/builder/status' || p.startsWith('/log-root/') || p.startsWith('/log/') || p.startsWith('/logs/')) {
        return highFreqLimiter(req, res, next);
    }
    return defaultLimiter(req, res, next);
});

// Body parsing middleware
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true, limit: '10mb' }));

// CORS middleware
app.use(corsMiddleware);

// Static files
app.use(express.static(path.join(__dirname, '..', 'public')));

// Routes
app.use('/api', apiRoutes);
app.use('/', dashboardRoutes);
app.use('/', reportRoutes);
app.use('/', healthRoutes);

// 404 handler
app.use((req, res) => {
    res.status(404).json({ error: 'Not found' });
});

// Error handler
app.use(errorHandler);

// Initialize server
async function startServer() {
    try {
        // Ensure required directories exist
        await fileService.ensureDirectories();
        
        // Start server
        const server = app.listen(config.server.port, config.server.host, () => {
            console.log(`
╔════════════════════════════════════════════════════════╗
║       CUBRID Test Report Server Started                 ║
╠════════════════════════════════════════════════════════╣
║  Version:     ${config.app.version.padEnd(42, ' ')}║
║  Environment: ${config.app.environment.padEnd(42, ' ')}║
║  Port:        ${String(config.server.port).padEnd(42, ' ')}║
║  Local IP:    ${config.server.localIp.padEnd(42, ' ')}║
║  Dashboard:   http://localhost:${config.server.port.toString().padEnd(25, ' ')}║
╚════════════════════════════════════════════════════════╝
            `);
        });
        
        // Server error handling
        server.on('error', (error) => {
            if (error.code === 'EADDRINUSE') {
                console.error(`Port ${config.server.port} is already in use. Please use a different port.`);
                process.exit(1);
            } else if (error.code === 'EACCES') {
                console.error(`Permission denied. Cannot bind to port ${config.server.port}.`);
                process.exit(1);
            } else {
                console.error('Server error:', error);
                // Don't exit on other errors - let the server try to recover
            }
        });
        
        // Connection timeout handling (prevent hanging connections)
        server.timeout = 120000; // 2 minutes
        server.keepAliveTimeout = 65000; // 65 seconds (just above typical load balancer timeout)
        server.headersTimeout = 66000; // 66 seconds (must be > keepAliveTimeout)
        
        // Track server start time for uptime monitoring
        const serverStartTime = Date.now();
        let isShuttingDown = false;
        
        // Graceful shutdown handler
        const gracefulShutdown = (signal) => {
            if (isShuttingDown) {
                return;
            }
            isShuttingDown = true;
            console.log(`${signal} received, closing server gracefully...`);
            
            // Stop accepting new connections
            server.close(() => {
                console.log('Server closed');
                process.exit(0);
            });
            
            // Force close after 10 seconds if graceful shutdown fails
            setTimeout(() => {
                console.error('Forced shutdown after timeout');
                process.exit(1);
            }, 10000);
        };
        
        // Graceful shutdown
        process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
        process.on('SIGINT', () => gracefulShutdown('SIGINT'));
        
        // Handle unhandled promise rejections
        process.on('unhandledRejection', (reason, promise) => {
            console.error('Unhandled Promise Rejection at:', promise);
            console.error('Reason:', reason);
            // Log but don't exit - keep the server running
            // TODO: Add log to a file or monitoring service
        });
        
        // Handle uncaught exceptions
        process.on('uncaughtException', (error) => {
            console.error('Uncaught Exception:', error);
            console.error('Stack:', error.stack);
            // Log but don't exit - keep the server running
            // Only exit on critical errors
            if (error.code === 'EADDRINUSE' || error.code === 'EACCES') {
                console.error('Critical error - exiting...');
                process.exit(1);
            }
        });
        
        // Handle warnings
        process.on('warning', (warning) => {
            console.warn('Warning:', warning.name);
            console.warn('Message:', warning.message);
            console.warn('Stack:', warning.stack);
        });
        
        // Keep-alive: Set up a heartbeat to ensure process stays alive
        const heartbeat = setInterval(() => {
            // This helps detect if the event loop is blocked
            const timestamp = new Date().toISOString();
            const uptime = Math.floor((Date.now() - serverStartTime) / 1000);
            if (config.app.environment === 'development') {
                console.log(`[Heartbeat] Server alive at ${timestamp} (uptime: ${uptime}s)`);
            }
        }, 3600000); // Every hour
        
        // Internal health check - verify server is still responding
        const healthCheckInterval = setInterval(async () => {
            try {
                // Simple check: verify server is listening
                if (!server.listening) {
                    console.error('[Health Check] Server is not listening!');
                    return;
                }
                
                // Check memory usage
                const memUsage = process.memoryUsage();
                const memUsageMB = {
                    rss: Math.round(memUsage.rss / 1024 / 1024),
                    heapTotal: Math.round(memUsage.heapTotal / 1024 / 1024),
                    heapUsed: Math.round(memUsage.heapUsed / 1024 / 1024),
                    external: Math.round(memUsage.external / 1024 / 1024)
                };
                
                // Warn if memory usage is high (> 1GB RSS)
                if (memUsageMB.rss > 1024) {
                    console.warn(`[Health Check] High memory usage: ${JSON.stringify(memUsageMB)}`);
                }
                
                if (config.app.environment === 'development') {
                    console.log(`[Health Check] Server healthy - Memory: ${memUsageMB.rss}MB RSS`);
                }
            } catch (err) {
                console.error('[Health Check] Error during health check:', err);
            }
        }, 300000); // Every 5 minutes
        
        // Clear intervals on shutdown
        process.on('exit', () => {
            clearInterval(heartbeat);
            clearInterval(healthCheckInterval);
        });
        
        // Export server instance for testing/monitoring
        app.set('server', server);
        app.set('serverStartTime', serverStartTime);
        
    } catch (err) {
        console.error('Failed to start server:', err);
        process.exit(1);
    }
}

// Start the server
if (require.main === module) {
    startServer();
}

module.exports = app;
