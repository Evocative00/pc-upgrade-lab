import { handlePriceRequest } from "../src/provider";
import type { WorkerEnv } from "../src/contract";

// Bundled exclusively by the test harness. The deployed entry point never reads this header.
export default {
  fetch(request: Request, env: WorkerEnv): Response {
    const fixedNow = request.headers.get("X-Test-Now");
    return handlePriceRequest(request, env, fixedNow === null ? Date.now() : Date.parse(fixedNow));
  },
};
