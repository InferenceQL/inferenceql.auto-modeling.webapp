import babel from '@rollup/plugin-babel';
import commonjs from '@rollup/plugin-commonjs';
import json from '@rollup/plugin-json';
import { nodeResolve } from '@rollup/plugin-node-resolve';
import replace from '@rollup/plugin-replace';

export default {
  input: 'resources/js/index.js',
  output: {
    file: 'resources/js/main.js',
    format: 'iife',
  },
  context: 'this',
  plugins: [
    nodeResolve({
      browser: true,
      dedupe: ['react', 'react-dom'],
      extensions: ['.js', '.jsx'],
    }),
    commonjs({ strictRequires: "auto" }),
    babel({
      babelHelpers: 'bundled',
      extensions: ['.jsx'],
      presets: ['@babel/preset-react'],
    }),
    json(),
    replace({
      'preventAssignment': true,
      'process.env.NODE_ENV': JSON.stringify('development'),
    })
  ],
}
