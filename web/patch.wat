(module
 (memory (export "memory") 1 16384)
 (func (export "patch") (param $offset i32) (param $guard i32) (param $replacement i32) (param $length i32) (result i32)
  (local $i i32)
  (block $checked (loop $check
   (br_if $checked (i32.ge_u (local.get $i) (local.get $length)))
   (if (i32.ne (i32.load8_u (i32.add (local.get $offset) (local.get $i))) (i32.load8_u (i32.add (local.get $guard) (local.get $i)))) (then (return (i32.const 0))))
   (local.set $i (i32.add (local.get $i) (i32.const 1))) (br $check)))
  (memory.copy (local.get $offset) (local.get $replacement) (local.get $length))
  (i32.const 1))
)
