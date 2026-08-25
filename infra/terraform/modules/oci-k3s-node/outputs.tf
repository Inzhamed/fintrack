output "public_ip" {
  description = "Public address of the node. Point the DNS A record at this."
  value       = oci_core_instance.k3s.public_ip
}

output "instance_id" {
  description = "OCID of the instance."
  value       = oci_core_instance.k3s.id
}

output "ssh_command" {
  description = "Ready-made SSH command for the node."
  value       = "ssh ubuntu@${oci_core_instance.k3s.public_ip}"
}

output "kubeconfig_command" {
  description = <<-EOT
    Fetches the cluster's kubeconfig and rewrites its server address.

    k3s writes 127.0.0.1 into the file, which is correct on the node and useless anywhere
    else, so the address has to be replaced with the public IP before the config works
    remotely.
  EOT
  value = join(" ", [
    "ssh ubuntu@${oci_core_instance.k3s.public_ip}",
    "'sudo cat /etc/rancher/k3s/k3s.yaml'",
    "| sed 's/127.0.0.1/${oci_core_instance.k3s.public_ip}/'",
    "> ~/.kube/fintrack.yaml",
  ])
}

output "vcn_id" {
  description = "OCID of the virtual network."
  value       = oci_core_vcn.this.id
}
