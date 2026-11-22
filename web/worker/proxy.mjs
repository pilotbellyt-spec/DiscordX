const UPSTREAM = 'https://tracker.vendetta.rocks';
const allowed = /^\/tracker\/(index|download\/356207\/(base|config\.(arm64_v8a|x86_64|hdpi)))$/;
export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (!url.pathname.startsWith('/tracker/')) return env.ASSETS.fetch(request);
    if (request.method !== 'GET' || !allowed.test(url.pathname)) return new Response('Not found', {status: 404});
    const key = new Request(url.origin + url.pathname);
    const cached = await caches.default.match(key);
    if (cached) return cached;
    const upstream = await fetch(UPSTREAM + url.pathname, {redirect: 'follow'});
    const headers = new Headers({
      'Content-Type': upstream.headers.get('Content-Type') || 'application/octet-stream',
      'Cache-Control': upstream.ok && url.pathname.includes('/download/') ? 'public,max-age=31536000,immutable' : 'public,max-age=300',
      'X-Content-Type-Options': 'nosniff'
    });
    if (upstream.headers.has('Content-Length')) headers.set('Content-Length', upstream.headers.get('Content-Length'));
    const response = new Response(upstream.body, {status: upstream.status, headers});
    if (upstream.ok || upstream.status === 404) ctx.waitUntil(caches.default.put(key, response.clone()));
    return response;
  }
};
