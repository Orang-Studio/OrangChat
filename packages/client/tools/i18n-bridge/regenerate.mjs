#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";

const here = path.dirname(fileURLToPath(import.meta.url));
const i18nDir = path.resolve(here, "../../src/lib/i18n");
const jsonDir = path.resolve(here, "../../i18n-weblate");
const locales = ["lt", "zh", "hi", "es", "ar", "fr", "bn", "pt", "ru", "ur"];

function unwrapObjectLiteral(node) {
  let current = node;
  while (ts.isAsExpression(current) || ts.isTypeAssertionExpression(current)) {
    current = current.expression;
  }
  return current;
}

function findObjectLiteral(source, exportName) {
  let objectLiteral;
  source.forEachChild((node) => {
    if (!ts.isVariableStatement(node)) return;
    for (const declaration of node.declarationList.declarations) {
      if (
        ts.isIdentifier(declaration.name) &&
        declaration.name.text === exportName &&
        declaration.initializer
      ) {
        objectLiteral = unwrapObjectLiteral(declaration.initializer);
      }
    }
  });
  if (!objectLiteral || !ts.isObjectLiteralExpression(objectLiteral)) {
    throw new Error(`Missing export const ${exportName}`);
  }
  return objectLiteral;
}

function serializeBody(order, values) {
  const lines = ["{"];
  for (const key of order) {
    if (!Object.hasOwn(values, key)) continue;
    lines.push(`  ${JSON.stringify(key)}: ${JSON.stringify(values[key])},`);
  }
  lines.push("}");
  return lines.join("\n");
}

const enOrder = Object.keys(JSON.parse(readFileSync(path.join(jsonDir, "en.json"), "utf8")));
const enKeys = new Set(enOrder);
const touched = [];

for (const locale of locales) {
  const values = JSON.parse(readFileSync(path.join(jsonDir, `${locale}.json`), "utf8"));
  const stale = Object.keys(values).filter((key) => !enKeys.has(key));
  if (stale.length > 0) {
    console.warn(`${locale}.json: dropping ${stale.length} stale keys`);
  }

  const filePath = path.join(i18nDir, `${locale}.ts`);
  const text = readFileSync(filePath, "utf8");
  const source = ts.createSourceFile(filePath, text, ts.ScriptTarget.Latest, true);
  const objectLiteral = findObjectLiteral(source, locale);
  const body = serializeBody(enOrder, values);
  const newText = text.slice(0, objectLiteral.getStart(source)) + body + text.slice(objectLiteral.getEnd());

  writeFileSync(filePath, newText, "utf8");
  touched.push(filePath);
  console.log(`${locale}.json -> ${locale}.ts`);
}

try {
  execFileSync("pnpm", ["exec", "prettier", "--no-single-quote", "--write", ...touched], {
    cwd: path.resolve(here, "../.."),
    stdio: "inherit",
  });
} catch (error) {
  console.warn(`Prettier formatting failed: ${error.message}`);
}
