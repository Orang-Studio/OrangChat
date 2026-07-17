import { prisma } from '../src/prisma.js';

/**
 * Dev seed. Fleshed out in Phase 2 (a demo server, channels, and users).
 * For now it just proves the DB connection + client generation work.
 */
async function main(): Promise<void> {
  const userCount = await prisma.user.count();
  // eslint-disable-next-line no-console
  console.log(`🌱 Seed connected. Users in DB: ${userCount}`);
}

main()
  .catch((err) => {
    // eslint-disable-next-line no-console
    console.error(err);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
