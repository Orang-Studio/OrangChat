import { prisma } from '../src/prisma.js';
/**
 * Dev seed. Fleshed out in Phase 2 a demo server.
 */
async function main(): Promise<void> {
  const userCount = await prisma.user.count();
  console.log(`Seed connected. Users in DB: ${userCount}`);
}
main()
  .catch((err) => {
    console.error(err);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());