#!/usr/bin/env node
import ts from "typescript";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const i18nDir = path.resolve(here, "../../src/lib/i18n");
const outDir = path.resolve(here, "../../i18n-weblate");
const locales = ["lt", "zh", "hi", "es", "ar", "fr", "bn", "pt", "ru", "ur"];

function unwrapObjectLiteral(node) {
  let current = node;
  while (ts.isAsExpression(current) || ts.isTypeAssertionExpression(current)) {
    current = current.expression;
  }
  return current;
}

function extractCatalog(filePath, exportName) {
  const text = readFileSync(filePath, "utf8");
  const source = ts.createSourceFile(filePath, text, ts.ScriptTarget.Latest, true);
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
    throw new Error(`${filePath}: missing export const ${exportName}`);
  }

  const pairs = [];
  for (const property of objectLiteral.properties) {
    if (!ts.isPropertyAssignment(property) || !ts.isStringLiteral(property.name)) continue;
    if (!ts.isStringLiteral(property.initializer)) {
      console.warn(`Skipping non-string value for ${property.name.text}`);
      continue;
    }
    pairs.push([property.name.text, property.initializer.text]);
  }
  return pairs;
}

function writeJson(name, pairs) {
  mkdirSync(outDir, { recursive: true });
  const object = Object.fromEntries(pairs);
  writeFileSync(
    path.join(outDir, `${name}.json`),
    `${JSON.stringify(object, null, 2)}\n`,
    "utf8",
  );
  return Object.keys(object).length;
}

const enPairs = extractCatalog(path.join(i18nDir, "en.ts"), "en");
console.log(`en.ts -> en.json (${writeJson("en", enPairs)} keys)`);

for (const locale of locales) {
  const pairs = extractCatalog(path.join(i18nDir, `${locale}.ts`), locale);
  console.log(`${locale}.ts -> ${locale}.json (${writeJson(locale, pairs)} keys)`);
}
