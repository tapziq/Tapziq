function leading_spaces(text, stripped) {
  stripped = text
  sub(/^ */, "", stripped)
  return length(text) - length(stripped)
}

function element_name(text, name) {
  name = text
  sub(/^ *E: /, "", name)
  sub(/ .*/, "", name)
  return name
}

/^ *E: / {
  depth = leading_spaces($0)
  for (level in element_at_depth) {
    if (level + 0 >= depth) {
      delete element_at_depth[level]
    }
  }

  parent = ""
  parent_depth = -1
  for (level in element_at_depth) {
    if (level + 0 < depth && level + 0 > parent_depth) {
      parent_depth = level + 0
      parent = element_at_depth[level]
    }
  }

  name = element_name($0)
  is_query_package = name == "package" && parent == "queries"
  element_at_depth[depth] = name
  next
}

is_query_package \
    && /A: .*android:name\(/ \
    && index($0, "=\"" expected_package "\"") > 0 {
  found = 1
}

END {
  exit found ? 0 : 1
}
