import { z } from "zod";

export const LoginInputSchema = z.object({
  email: z.string().min(1).email(),
  password: z.string().min(1),
});

export type LoginInput = z.infer<typeof LoginInputSchema>;
