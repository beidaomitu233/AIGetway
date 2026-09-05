import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import vueTsConfig from '@vue/eslint-config-typescript'

export default tseslint.config(
  { ignores: ['dist/', 'node_modules/', 'coverage/'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  vueTsConfig(),
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: { parser: tseslint.parser },
    },
  },
  {
    rules: {
      'no-console': 'error',
      'no-debugger': 'error',
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      'vue/multi-word-component-names': 'off',
      // 可选属性语义由 exactOptionalPropertyTypes 表达，不提供运行时默认值。
      'vue/require-default-prop': 'off',
    },
  },
  {
    files: ['tests/**/*.ts'],
    rules: {
      // 测试宿主组件按用例内联定义。
      'vue/one-component-per-file': 'off',
    },
  },
)
