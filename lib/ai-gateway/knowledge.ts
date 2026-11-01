import { z } from "zod";
import { uuid } from "./spec";

export const KnowledgeData = z.strictObject({ hits: z.array(z.strictObject({
  citation: z.object({ document_id: uuid, version_id: uuid, chunk_id: uuid,
    locator: z.union([z.strictObject({ kind: z.literal("section"), value: z.string().min(1).max(160) }),
      z.strictObject({ kind: z.literal("page"), value: z.number().int().min(1).max(200) })]),
    source_url: z.string().regex(/^\/ai\/citations\/[0-9a-f-]{36}$/).optional(),
  }).transform(c => ({ document_id: c.document_id, version_id: c.version_id, chunk_id: c.chunk_id, locator: c.locator })),
  excerpt: z.string().min(1).max(2000), score: z.number(), retrieval_mode: z.enum(["lexical", "dense", "hybrid"]),
})).max(5) });
