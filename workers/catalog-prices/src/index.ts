import { handlePriceRequest } from "./provider";
import type { WorkerEnv } from "./contract";

export default {
  fetch(request: Request, env: WorkerEnv): Response {
    return handlePriceRequest(request, env, Date.now());
  },
};
