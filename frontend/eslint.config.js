// ESLint flat config (DOC-11 §3). Formatting is Prettier's job; eslint-config-prettier switches off the rules it owns.
import js from '@eslint/js';
import prettier from 'eslint-config-prettier';
import i18next from 'eslint-plugin-i18next';
import { importX } from 'eslint-plugin-import-x';
import { createTypeScriptImportResolver } from 'eslint-import-resolver-typescript';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import globals from 'globals';
import tseslint from 'typescript-eslint';

// Feature folders of DOC-34 §9.1. A feature never imports another one; shared code moves to components/ or lib/.
const FEATURES = [
  'map',
  'stops',
  'scorecard',
  'alerts',
  'overview',
  'ops-jobs',
  'ops-dlq',
  'ops-replay',
  'ops-controls',
  'ops-ticketing',
  'demo',
];

export default tseslint.config(
  {
    ignores: [
      'dist/',
      'coverage/',
      'playwright-report/',
      'test-results/',
      'src/routeTree.gen.ts',
      'src/api/generated/',
      'mock-public/',
      'public/',
    ],
  },
  js.configs.recommended,
  tseslint.configs.strictTypeChecked,
  tseslint.configs.stylisticTypeChecked,
  {
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    rules: {
      '@typescript-eslint/consistent-type-imports': 'error',
      '@typescript-eslint/restrict-template-expressions': ['error', { allowNumber: true }],
    },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    languageOptions: { globals: globals.browser },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
      i18next,
      'import-x': importX,
    },
    settings: {
      'import-x/resolver-next': [createTypeScriptImportResolver({ project: './tsconfig.app.json' })],
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['error', { allowConstantExport: true }],
      // Every user-visible string lives in src/i18n/en.ts (DR-48, DOC-37 CP-02).
      'i18next/no-literal-string': ['error', { mode: 'jsx-only' }],
      // XSS: React escapes by default; nothing may opt out of it (DOC-27 §7).
      'no-restricted-syntax': [
        'error',
        {
          selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
          message: 'dangerouslySetInnerHTML is forbidden (DOC-27).',
        },
      ],
      // Requests go through src/api/ (ADR-0020).
      'no-restricted-globals': [
        'error',
        { name: 'fetch', message: 'Call the API through src/api/ (ADR-0020).' },
        { name: 'EventSource', message: 'Use the realtime provider (DOC-26 §8).' },
      ],
      'import-x/no-restricted-paths': [
        'error',
        {
          zones: [
            ...FEATURES.map((feature) => ({
              target: `./src/features/${feature}`,
              from: './src/features',
              except: [`./${feature}`],
              message: 'Features do not import each other; move shared code to components/ or lib/ (DOC-34 §9.1).',
            })),
            {
              target: './src/components',
              from: ['./src/features', './src/api'],
              message: 'components/ stays independent of features/ and api/ (DOC-34 §9.1).',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/api/**', 'src/realtime/**', 'src/test/**', 'src/mocks/**'],
    rules: { 'no-restricted-globals': 'off' },
  },
  {
    files: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/mocks/**'],
    rules: { 'i18next/no-literal-string': 'off' },
  },
  {
    // The /_ui catalogue (DOC-35 §10) is a developer page whose props are sample data (ids, colours, iso durations);
    // its headings and sentences still come from src/i18n/catalog.ts.
    files: ['src/catalog/**'],
    rules: { 'i18next/no-literal-string': 'off' },
  },
  {
    // shadcn/ui primitives export variants next to components; route files export `Route`, and the router plugin
    // splits their components into separate chunks that refresh on their own. A context module keeps its provider and
    // hook together.
    files: [
      'src/components/ui/**',
      'src/routes/**',
      'src/app/theme-provider.tsx',
      'src/app/auth.tsx',
      'src/app/env-context.tsx',
      'src/app/freshness.tsx',
      'src/lib/business-clock.tsx',
    ],
    rules: { 'react-refresh/only-export-components': 'off' },
  },
  {
    // `throw notFound()` is TanStack Router's way to answer 404 from beforeLoad (DOC-34 §5.2); it is a plain object.
    files: ['src/routes/**'],
    rules: {
      '@typescript-eslint/only-throw-error': [
        'error',
        { allow: [{ from: 'package', package: '@tanstack/router-core', name: 'NotFoundError' }] },
      ],
    },
  },
  {
    files: ['*.{js,ts}', 'e2e/**', 'scripts/**'],
    languageOptions: { globals: globals.node },
  },
  {
    files: ['**/*.js', '**/*.mjs'],
    extends: [tseslint.configs.disableTypeChecked],
  },
  prettier,
);
