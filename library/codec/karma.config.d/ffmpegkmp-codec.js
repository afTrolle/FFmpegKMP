// The browser tests decode and encode in FFmpegKMP's worker, which needs cross-origin isolation for its threads.
config.client.useIframe = false;
config.client.mocha = {
    ...(config.client.mocha || {}),
    timeout: 120000
};
config.browserNoActivityTimeout = 120000;

config.files.push(...[
    "kotlin/ffmpegkmp-worker.mjs",
    "kotlin/ffmpegkmp.mjs",
    "kotlin/ffmpegkmp.wasm",
    "kotlin/video-decoder/*.mp4"
].map(pattern => ({
    pattern,
    included: false,
    served: true,
    watched: false
})));

function FFmpegKmpCodecIsolationMiddlewareFactory() {
    return function (_request, response, next) {
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Embedder-Policy", "require-corp");
        next();
    };
}

config.beforeMiddleware = [
    ...(config.beforeMiddleware || []),
    "ffmpegkmp-codec-cross-origin-isolation"
];
config.plugins.push({
    "middleware:ffmpegkmp-codec-cross-origin-isolation": [
        "factory",
        FFmpegKmpCodecIsolationMiddlewareFactory
    ]
});
