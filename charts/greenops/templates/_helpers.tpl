{{- define "greenops.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "greenops.fullname" -}}
{{- printf "%s-operator" (include "greenops.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "greenops.labels" -}}
app.kubernetes.io/name: {{ include "greenops.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end -}}

{{- define "greenops.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "greenops.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "greenops.flinkNamespace" -}}
{{- default .Release.Namespace .Values.controller.flinkNamespace -}}
{{- end -}}

{{- define "greenops.telemetryEndpoint" -}}
{{- if .Values.controller.telemetryEndpoint -}}
{{- .Values.controller.telemetryEndpoint -}}
{{- else -}}
{{- printf "http://%s-telemetry.%s.svc.cluster.local:8080/telemetry/status" (include "greenops.name" .) .Release.Namespace -}}
{{- end -}}
{{- end -}}

{{- define "greenops.flinkRestEndpoint" -}}
{{- if .Values.controller.flinkRestEndpoint -}}
{{- .Values.controller.flinkRestEndpoint -}}
{{- else -}}
{{- printf "http://%s-rest.%s.svc.cluster.local:8081" .Values.controller.flinkJobName (include "greenops.flinkNamespace" .) -}}
{{- end -}}
{{- end -}}
