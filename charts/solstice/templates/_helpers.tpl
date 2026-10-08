{{- define "solstice.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "solstice.fullname" -}}
{{- printf "%s-operator" (include "solstice.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "solstice.labels" -}}
app.kubernetes.io/name: {{ include "solstice.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end -}}

{{- define "solstice.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "solstice.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "solstice.flinkNamespace" -}}
{{- default .Release.Namespace .Values.controller.flinkNamespace -}}
{{- end -}}

{{- define "solstice.telemetryEndpoint" -}}
{{- if .Values.controller.telemetryEndpoint -}}
{{- .Values.controller.telemetryEndpoint -}}
{{- else -}}
{{- printf "http://%s-telemetry.%s.svc.cluster.local:8080/telemetry/status" (include "solstice.name" .) .Release.Namespace -}}
{{- end -}}
{{- end -}}

{{- define "solstice.forecastEndpoint" -}}
{{- printf "http://%s-telemetry.%s.svc.cluster.local:8080/telemetry/forecast" (include "solstice.name" .) .Release.Namespace -}}
{{- end -}}

{{- define "solstice.flinkRestEndpoint" -}}
{{- if .Values.controller.flinkRestEndpoint -}}
{{- .Values.controller.flinkRestEndpoint -}}
{{- else -}}
{{- printf "http://%s-rest.%s.svc.cluster.local:8081" .Values.controller.flinkJobName (include "solstice.flinkNamespace" .) -}}
{{- end -}}
{{- end -}}
