{{- define "production-simulator.name" -}}
production-simulator
{{- end }}
{{- define "production-simulator.fullname" -}}
{{ .Release.Name }}-{{ include "production-simulator.name" . }}
{{- end }}
