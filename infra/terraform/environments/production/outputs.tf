output "public_ip" {
  description = "Point the DNS A record here."
  value       = module.k3s_node.public_ip
}

output "ssh_command" {
  value = module.k3s_node.ssh_command
}

output "kubeconfig_command" {
  value = module.k3s_node.kubeconfig_command
}
