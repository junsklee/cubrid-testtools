/**
 * Error Handler Middleware
 */

function errorHandler(err, req, res, next) {
    console.error('Error:', err.stack);
    
    // Don't leak error details in production
    const isDevelopment = process.env.NODE_ENV === 'development';
    
    res.status(err.status || 500).json({
        error: {
            message: err.message,
            status: err.status || 500,
            ...(isDevelopment && { stack: err.stack })
        }
    });
}

module.exports = errorHandler;
