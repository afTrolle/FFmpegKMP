const ffmpegKmpAssets = [
    "kotlin/ffmpegkmp-worker.mjs",
    "kotlin/ffmpegkmp.mjs",
    "kotlin/ffmpegkmp.wasm",
    "kotlin/big-buck-bunny-1s.mp4"
];

config.client.useIframe = false;
config.client.mocha = {
    ...(config.client.mocha || {}),
    timeout: 120000
};
config.browserNoActivityTimeout = 120000;

config.files.push(...ffmpegKmpAssets.map(pattern => ({
    pattern,
    included: false,
    served: true,
    watched: false
})));

function FFmpegKmpCrossOriginIsolationMiddlewareFactory() {
    return function (_request, response, next) {
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Embedder-Policy", "require-corp");
        next();
    };
}

config.beforeMiddleware = [
    ...(config.beforeMiddleware || []),
    "ffmpegkmp-cross-origin-isolation"
];
config.plugins.push({
    "middleware:ffmpegkmp-cross-origin-isolation": [
        "factory",
        FFmpegKmpCrossOriginIsolationMiddlewareFactory
    ]
});

config.customHeaders = [
    ...(config.customHeaders || []),
    {
        match: ".*",
        name: "Cross-Origin-Opener-Policy",
        value: "same-origin"
    },
    {
        match: ".*",
        name: "Cross-Origin-Embedder-Policy",
        value: "require-corp"
    }
];
