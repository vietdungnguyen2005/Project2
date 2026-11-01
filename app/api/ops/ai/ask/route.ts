import { handleQA } from "@/lib/ai-qa/handler";
export const runtime="nodejs";
export const dynamic="force-dynamic";
export const POST=(request:Request)=>handleQA(request,"ask");
