const test = require("node:test"),
  assert = require("node:assert/strict"),
  fs = require("node:fs"),
  path = require("node:path"),
  { parse } = require("@babel/parser");
const root = path.resolve(__dirname, "../../main/resources/web");
test("native modules resolve every local named import and keep the entry point small", () => {
  const modules = new Map();
  function inspect(file) {
    if (modules.has(file)) return modules.get(file);
    const source = fs.readFileSync(file, "utf8"),
      ast = parse(source, { sourceType: "module" });
    const value = { exports: new Set(), imports: [] };
    modules.set(file, value);
    for (const node of ast.program.body) {
      if (node.type === "ExportNamedDeclaration") {
        if (node.declaration?.id) value.exports.add(node.declaration.id.name);
        for (const declaration of node.declaration?.declarations || [])
          if (declaration.id.name) value.exports.add(declaration.id.name);
        for (const specifier of node.specifiers || [])
          value.exports.add(specifier.exported.name);
      }
      if (
        node.type === "ImportDeclaration" &&
        node.source.value.startsWith(".")
      ) {
        const target = path.resolve(path.dirname(file), node.source.value);
        assert.ok(target.startsWith(root + path.sep));
        assert.ok(fs.existsSync(target), "Missing module " + target);
        value.imports.push([node, target]);
        inspect(target);
      }
    }
    return value;
  }
  inspect(path.join(root, "app.js"));
  for (const feature of [
    "case",
    "appeal",
    "inventory",
    "deliveries",
    "templates",
    "ban",
    "portal",
  ])
    inspect(path.join(root, feature + ".js"));
  for (const [file, module] of modules)
    for (const [node, target] of module.imports)
      for (const specifier of node.specifiers)
        if (specifier.type === "ImportSpecifier")
          assert.ok(
            modules.get(target).exports.has(specifier.imported.name),
            file +
              " imports missing " +
              specifier.imported.name +
              " from " +
              target,
          );
  assert.ok(modules.size > 20);
  assert.ok(
    fs.readFileSync(path.join(root, "app.js"), "utf8").split("\n").length < 10,
  );
});
