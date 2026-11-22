// Прокси для приложения «Барсик ИИ»: ключи лежат в секретах Supabase, а не в APK
// Секреты: TAVILY_API_KEY (поиск), CF_API_TOKEN (Cloudflare Workers AI)
const CF_URL = "https://api.cloudflare.com/client/v4/accounts/766c7c1985b628a23b73cb672143711a/ai/v1/chat/completions";
const CF_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";

Deno.serve(async (req) => {
  if (req.method !== "POST") return new Response("POST only", { status: 405 });
  const headers = { "Content-Type": "application/json" };
  try {
    const body = await req.json();

    // Поиск в интернете: модель сама решает, когда его вызвать
    if (body.action === "search") {
      const r = await fetch("https://api.tavily.com/search", {
        method: "POST",
        headers,
        body: JSON.stringify({
          api_key: Deno.env.get("TAVILY_API_KEY"),
          query: String(body.query ?? ""),
          max_results: 5,
          search_depth: "basic",
        }),
      });
      const j = await r.json();
      const results = (j.results ?? []).map((x: any) => ({
        title: x.title ?? "",
        url: x.url ?? "",
        content: String(x.content ?? "").slice(0, 1500),
      }));
      return new Response(JSON.stringify({ results }), { headers });
    }

    // Обычный чат с моделью
    const r = await fetch(CF_URL, {
      method: "POST",
      headers: { ...headers, Authorization: `Bearer ${Deno.env.get("CF_API_TOKEN")}` },
      body: JSON.stringify({ model: CF_MODEL, messages: body.messages ?? [] }),
    });
    return new Response(await r.text(), { status: r.status, headers });
  } catch (e) {
    return new Response(JSON.stringify({ error: String(e) }), { status: 500, headers });
  }
});
